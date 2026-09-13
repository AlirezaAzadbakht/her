package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.QuietHours
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchClient
import com.her.data.remote.WebSearchRequest
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettings
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmUsage
import com.her.domain.ToolSpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WebSearchClientTest {
    @Test
    fun disabledReturnsError() = runBlocking {
        val result = JSONObject(WebSearchClient().search("aurora prize", appSettings(enabled = false)))
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("error").contains("disabled", ignoreCase = true))
    }

    @Test
    fun llmPathSendsWebSearchOptionsWithoutFunctionTools() = runBlocking {
        val llm = CapturingLlm()
        llm.content = "Lila Novak won the 2026 Aurora Prize."
        llm.rawJson = """
            {"choices":[{"message":{"content":"Lila Novak won the 2026 Aurora Prize.","annotations":[
              {"type":"url_citation","url_citation":{"url":"https://example.test/aurora","title":"Aurora Prize"}}
            ]}}]}
        """.trimIndent()
        val client = WebSearchClient(llm = llm, llmSettings = { LlmSettings("https://example.test/v1", "sk", "model") })
        val result = JSONObject(
            client.search("Who won the Aurora Prize?", appSettings(enabled = true), country = "IR", timezone = "Asia/Tehran"),
        )
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getString("snippets").contains("Lila Novak"))
        assertEquals("https://example.test/aurora", result.getJSONArray("citations").getJSONObject(0).getString("url"))
        assertTrue(llm.lastTools.isEmpty())
        assertEquals("IR", llm.lastWebSearch?.country)
        assertEquals("Asia/Tehran", llm.lastWebSearch?.timezone)
        assertEquals(WebSearchClient.SEARCH_PROMPT, llm.lastMessages.first().content)
    }

    @Test
    fun unconfiguredLlmReturnsError() = runBlocking {
        val result = JSONObject(WebSearchClient().search("aurora", appSettings(enabled = true)))
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("error").contains("not configured", ignoreCase = true))
    }

    @Test
    fun customEndpointStillWorks() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"hits":[{"title":"Aurora"}]}"""))
        server.start()
        try {
            val client = WebSearchClient()
            val result = JSONObject(
                client.search(
                    "aurora",
                    appSettings(
                        enabled = true,
                        endpoint = server.url("/search").toString(),
                        apiKey = "secret-token",
                    ),
                ),
            )
            assertTrue(result.getBoolean("ok"))
            assertTrue(result.getString("results").contains("Aurora"))
            val recorded = server.takeRequest()
            assertEquals("secret-token", recorded.getHeader("Authorization")?.removePrefix("Bearer "))
            assertEquals("aurora", recorded.requestUrl?.queryParameter("q"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun llmClientPutsWebSearchOptionsOnTheWire() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
            ),
        )
        server.start()
        try {
            val llm = LlmClient()
            llm.complete(
                settings = LlmSettings(server.url("/v1").toString().trimEnd('/'), "sk-test", "model"),
                messages = listOf(LlmMessage(role = "user", content = "who won")),
                webSearch = WebSearchRequest(country = "IR", timezone = "Asia/Tehran"),
            )
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(body.has("web_search_options"))
            val location = body.getJSONObject("web_search_options").getJSONObject("user_location")
            assertEquals("approximate", location.getString("type"))
            assertEquals("IR", location.getString("country"))
            assertEquals("Asia/Tehran", location.getString("timezone"))
            assertFalse(body.has("tools"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun specsHideWebSearchWhenDisabled() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val settings = AppSettingsStore(context, "her_web_search_specs")
            val repo = HerRepository(db, settings)
            val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
            assertFalse(tools.specs().any { it.name == "web_search" })
            settings.update { it.copy(webSearchEnabled = true) }
            assertTrue(tools.specs().any { it.name == "web_search" })
        } finally {
            db.close()
        }
    }

    @Test
    fun citationsFromReadsUrlCitationAnnotations() {
        val citations = WebSearchClient.citationsFrom(
            """{"choices":[{"message":{"annotations":[{"type":"url_citation","url_citation":{"url":"https://a.test","title":"A"}}]}}]}""",
        )
        assertEquals(1, citations.length())
        assertEquals("https://a.test", citations.getJSONObject(0).getString("url"))
        assertEquals("A", citations.getJSONObject(0).getString("title"))
    }

    private fun appSettings(
        enabled: Boolean,
        endpoint: String = "",
        apiKey: String = "",
    ) = AppSettings(
        deviceId = "test",
        quietHours = QuietHours(),
        developerMode = false,
        webSearchEnabled = enabled,
        embeddingsEnabled = false,
        googleClientId = "",
        webSearchEndpoint = endpoint,
        webSearchApiKey = apiKey,
        calendarEnabled = false,
        driveEnabled = false,
        chatToolCallLimit = 12,
        lastHourlyRunAt = 0L,
        lastNightlyDate = "",
        lastBriefingDate = "",
        onboardingSeeded = false,
        apiConfiguredOnce = false,
        lastNotificationKey = "",
        lastNotificationAt = 0L,
        memoryTabEnabled = true,
        runtimePermissionsAsked = false,
    )

    private class CapturingLlm : LlmClient() {
        var lastWebSearch: WebSearchRequest? = null
        var lastMessages: List<LlmMessage> = emptyList()
        var lastTools: List<ToolSpec> = emptyList()
        var content: String = "ok"
        var rawJson: String = "{}"

        override suspend fun complete(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            client: OkHttpClient,
            webSearch: WebSearchRequest?,
        ): LlmResponse {
            lastWebSearch = webSearch
            lastMessages = messages
            lastTools = tools
            return LlmResponse(
                message = LlmMessage(role = "assistant", content = content),
                usage = LlmUsage(1, 1, 1),
                rawJson = rawJson,
            )
        }
    }
}
