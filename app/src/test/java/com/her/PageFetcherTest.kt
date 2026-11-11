package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.PAGE_NOTE
import com.her.agent.tools.ToolRegistry
import com.her.agent.tools.pagePayload
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.FetchedPage
import com.her.data.remote.MAX_PAGE_BYTES
import com.her.data.remote.WebSearchClient
import com.her.data.remote.fetchPage
import com.her.data.remote.htmlToText
import com.her.data.remote.isPrivateAddress
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PageFetcherTest {
    private val http = OkHttpClient()

    @Test
    fun htmlBecomesReadableText() {
        val text = htmlToText(
            """
            <html><head><title>Ignored</title><style>p { color: red }</style></head>
            <body><!-- hidden --><script>alert("x < y")</script>
            <h1>Bread   &amp; butter</h1><p>Feed the&nbsp;starter<br>twice &#8212; daily &lt;3</p>
            <header>Top</header></body></html>
            """.trimIndent(),
        )
        assertEquals("Bread & butter\nFeed the starter\ntwice — daily <3\nTop", text)
    }

    @Test
    fun fetchReadsTitleAndTextFromAPage() = withServer { server ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Loaf</title></head><body><p>Bake at 230.</p></body></html>"),
        )
        val page = fetchPage(http, server.url("/loaf").toString(), blocked = { false })
        assertEquals("Loaf", page.title)
        assertEquals("Bake at 230.", page.text)
    }

    @Test
    fun bodyReadIsCapped() = withServer { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("a".repeat(MAX_PAGE_BYTES.toInt() + 50_000)))
        val page = fetchPage(http, server.url("/big").toString(), blocked = { false })
        assertEquals(MAX_PAGE_BYTES.toInt(), page.text.length)
    }

    @Test
    fun payloadFitsTheModelCapAndLabelsThePageAsData() {
        val payload = pagePayload(FetchedPage("https://a.test/", "T", "line \"quoted\"\n".repeat(5_000)))
        assertTrue(payload.toString().length <= AgentOrchestrator.MAX_TOOL_PAYLOAD_CHARS)
        assertTrue(payload.getBoolean("truncated"))
        assertTrue(payload.getString("text").length > 4_000)
        assertEquals(PAGE_NOTE, payload.getString("note"))
        assertTrue(PAGE_NOTE.contains("not instructions"))
    }

    @Test
    fun onlyHttpAndHttpsAreAccepted() {
        listOf("file:///etc/passwd", "ftp://example.org/a", "javascript:alert(1)", "content://sms", "not a link").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { fetchPage(http, url) }
        }
    }

    @Test
    fun privateAndLocalHostsAreRefused() {
        listOf(
            "http://127.0.0.1/", "http://localhost/", "http://10.0.0.1/", "http://172.16.5.4/", "http://192.168.1.1/admin",
            "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/", "http://0.0.0.0/", "http://[::1]/",
            "http://[fd00::1]/", "http://[fe80::1]/", "http://[::ffff:10.0.0.1]/",
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { fetchPage(http, url) }
        }
        assertFalse(isPrivateAddress(InetAddress.getByName("93.184.216.34")))
        assertFalse(isPrivateAddress(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")))
    }

    @Test
    fun redirectsAreCheckedAgain() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.1/secret"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "file:///etc/passwd"))
        val loopbackOnly: (InetAddress) -> Boolean = { !it.isLoopbackAddress && isPrivateAddress(it) }
        assertThrows(IllegalArgumentException::class.java) { fetchPage(http, server.url("/a").toString(), loopbackOnly) }
        assertThrows(IllegalArgumentException::class.java) { fetchPage(http, server.url("/b").toString(), loopbackOnly) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun toolIsAbsentWhenWebSearchIsOff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val settings = AppSettingsStore(context, "her_fetch_url_specs")
            val repo = HerRepository(db, settings)
            val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
            assertFalse(tools.specs().any { it.name == "fetch_url" })
            val refused = tools.execute("fetch_url", """{"url":"https://example.org/"}""")
            assertFalse(JSONObject(refused.payloadJson).getBoolean("ok"))
            settings.update { it.copy(webSearchEnabled = true) }
            assertTrue(tools.specs().any { it.name == "fetch_url" })
            val local = JSONObject(tools.execute("fetch_url", """{"url":"http://192.168.1.1/"}""").payloadJson)
            assertFalse(local.getBoolean("ok"))
            assertTrue(local.getString("error").contains("private"))
        } finally {
            db.close()
        }
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
