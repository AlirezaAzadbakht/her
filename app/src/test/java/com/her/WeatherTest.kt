package com.her

import android.app.Application
import com.her.data.remote.WeatherClient
import com.her.data.device.phoneLines
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WeatherTest {
    private val body = """
        {"daily":{"time":["2026-11-21","2026-11-22"],"weather_code":[61,0],
          "temperature_2m_max":[9.1,12.0],"temperature_2m_min":[3.2,4.0],
          "precipitation_probability_max":[80,5],"wind_speed_10m_max":[28.0,10.0],"wind_gusts_10m_max":[55.0,20.0]},
         "hourly":{"time":["2026-11-21T08:00","2026-11-21T14:00","2026-11-22T14:00"],"precipitation_probability":[20,70,90]}}
    """.trimIndent()

    @Test
    fun summarizesAndRoundsThePoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val out = WeatherClient(server.url("/v1/forecast").toString()).forecast(35.689123, 51.389987)!!
            val sent = server.takeRequest().requestUrl!!
            assertEquals("35.69", sent.queryParameter("latitude"))
            assertEquals("51.39", sent.queryParameter("longitude"))
            val today = out.getJSONArray("days").getJSONObject(0)
            assertEquals("rain", today.getString("conditions"))
            assertEquals(55.0, today.getDouble("gustsMaxKmh"), 0.0)
            assertEquals("14:00", today.getJSONArray("rainLikelyHours").getString(0))
            assertEquals(1, today.getJSONArray("rainLikelyHours").length())
            assertEquals("clear", out.getJSONArray("days").getJSONObject(1).getString("conditions"))
        }
    }

    @Test
    fun serverErrorIsNull() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            assertNull(WeatherClient(server.url("/").toString()).forecast(1.0, 2.0))
        }
    }

    @Test
    fun phoneSaysNothingWhenNothingMatters() {
        val now = ZonedDateTime.of(2026, 11, 21, 22, 0, 0, 0, ZoneId.of("UTC"))
        val ms = now.toInstant().toEpochMilli()
        assertTrue(phoneLines(null, 80, false, now).isEmpty())
        assertTrue(phoneLines(ms + 30 * 3_600_000L, 25, true, now).isEmpty())
        val lines = phoneLines(ms + 8 * 3_600_000L, 20, false, now)
        assertEquals(listOf("Next alarm: 6:00 AM tomorrow", "Battery 20%, not charging"), lines)
    }
}
