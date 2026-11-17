package com.her

import android.app.Application
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.data.db.HerDatabase
import com.her.data.remote.ImageReadException
import com.her.data.remote.ImageReadFailure
import com.her.data.remote.ImageReader
import com.her.data.remote.LlmClient
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ImageReaderTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private val server = MockWebServer()
    private val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        repo = HerRepository(db, AppSettingsStore(context, "image_reader_${System.nanoTime()}"), FakeClock(0))
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun reader() = ImageReader(repo, LlmClient()) { LlmSettings(server.url("/v1").toString().trimEnd('/'), "sk-test", "model") }

    @Test
    fun sendsTheImageAsAPartAndReturnsTheText() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Flight IR712, gate B4\nA boarding pass."}}]}"""))
        assertEquals("Flight IR712, gate B4\nA boarding pass.", reader().read(image))

        val user = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1)
        val parts = user.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun textOnlyMessagesKeepPlainStringContent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
        reader().read(image)
        val system = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(0)
        assertTrue(system.get("content") is String)
    }

    @Test
    fun aBadRequestMeansTheModelCannotReadImages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"image input is not supported"}}"""))
        try {
            reader().read(image)
            fail("expected ImageReadException")
        } catch (e: ImageReadException) {
            assertEquals(ImageReadFailure.UNSUPPORTED, e.failure)
        }
    }
}
