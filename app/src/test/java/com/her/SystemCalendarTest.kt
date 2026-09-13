package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.tools.ToolRegistry
import com.her.data.calendar.CalendarWindows
import com.her.data.calendar.FakeCalendarDataSource
import com.her.data.calendar.FakeGoogleCalendarClient
import com.her.data.calendar.GoogleCalendar
import com.her.data.calendar.SystemCalendar
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.core.RelativeTimeParser
import com.her.domain.CalendarSource
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SystemCalendarTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var settings: AppSettingsStore
    private lateinit var calendar: FakeCalendarDataSource
    private lateinit var google: FakeGoogleCalendarClient
    private lateinit var googleCalendar: GoogleCalendar
    private lateinit var tools: ToolRegistry
    private lateinit var contextBuilder: ContextBuilder

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = AppSettingsStore(context, "her_system_calendar_test_${System.nanoTime()}")
        repo = HerRepository(db, settings)
        calendar = FakeCalendarDataSource(context, granted = true)
        google = FakeGoogleCalendarClient()
        googleCalendar = GoogleCalendar(repo, google)
        settings.update { it.copy(calendarEnabled = true) }
        val ranker = HybridRanker(repo)
        tools = ToolRegistry(repo, ranker, settings, calendar, WebSearchClient(), googleCalendar = googleCalendar)
        contextBuilder = ContextBuilder(repo, ranker, settings, calendar, googleCalendar = googleCalendar)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createWritesDeviceAndRoom() = runBlocking {
        val result = tools.execute(
            "create_calendar_event",
            """{"title":"Call with Sara","when":"tomorrow at 10am"}""",
        )
        assertTrue(result.ok)
        assertEquals(1, calendar.all().size)
        assertEquals("Call with Sara", calendar.all().single().title)
        val stored = repo.calendarInRange(0, Long.MAX_VALUE).single()
        assertEquals(CalendarSource.SYSTEM, stored.source)
        assertEquals(calendar.all().single().id, CalendarWindows.deviceEventId(stored.externalId!!))
        assertTrue(JSONObject(result.payloadJson).getBoolean("systemWrite"))
    }

    @Test
    fun updateMovesDeviceEvent() = runBlocking {
        val original = parseWhen("tomorrow at 10am")
        val movedAt = parseWhen("tomorrow at 3pm")
        val externalId = calendar.seed("Roadmap review", original)
        val result = tools.execute(
            "update_calendar_event",
            """{"id":"$externalId","when":"tomorrow at 3pm"}""",
        )
        assertTrue(result.ok)
        assertEquals(1, calendar.all().size)
        assertEquals("Roadmap review", calendar.all().single().title)
        assertEquals(movedAt, calendar.all().single().startAt)
    }

    @Test
    fun deleteNeedsConfirmationThenRemovesDeviceEvent() = runBlocking {
        calendar.seed("Dentist", parseWhen("tomorrow at 10am"))
        contextBuilder.build()
        val first = tools.execute("delete_calendar_event", """{"id":"Dentist"}""")
        assertTrue(JSONObject(first.payloadJson).getBoolean("needsConfirmation"))
        assertEquals(1, calendar.all().size)
        val confirmId = JSONObject(first.payloadJson).getString("confirmId")
        val roomId = repo.calendarInRange(0, Long.MAX_VALUE).single().id
        val second = tools.execute("delete_calendar_event", """{"id":"$roomId","confirmId":"$confirmId"}""")
        assertTrue(second.ok)
        assertTrue(calendar.all().isEmpty())
        calendar.seed("Dentist", parseWhen("tomorrow at 10am"))
        contextBuilder.build()
        val confirmed = tools.execute("delete_calendar_event", """{"id":"Dentist","confirmed":true}""")
        assertTrue(confirmed.ok)
        assertTrue(calendar.all().isEmpty())
    }

    @Test
    fun withoutAccessStaysInternal() = runBlocking {
        calendar.granted = false
        settings.update { it.copy(calendarEnabled = false) }
        val result = tools.execute(
            "create_calendar_event",
            """{"title":"Solo note","when":"tomorrow at 10am"}""",
        )
        assertTrue(result.ok)
        assertTrue(calendar.all().isEmpty())
        assertEquals(CalendarSource.INTERNAL, repo.calendarInRange(0, Long.MAX_VALUE).single().source)
        assertTrue(!JSONObject(result.payloadJson).getBoolean("systemWrite"))
    }

    @Test
    fun contextListsSeededDeviceEvents() = runBlocking {
        calendar.seed("Dentist", parseWhen("tomorrow at 10am"))
        val bundle = contextBuilder.build().systemBundle
        assertTrue(bundle.contains("Dentist"))
        assertTrue(bundle.contains("System calendar"))
    }

    @Test
    fun contextListsTodaysPhoneAndGoogleMeetings() = runBlocking {
        calendar.seed("Team standup", parseWhen("today at 2pm"))
        google.granted = true
        google.seed("Design review", parseWhen("today at 4pm"))
        val bundle = contextBuilder.build().systemBundle
        assertTrue(bundle.contains("Team standup"))
        assertTrue(bundle.contains("Design review"))
        assertTrue(bundle.contains("System calendar"))
        assertTrue(bundle.contains("Google calendar"))
    }

    @Test
    fun getCalendarEventsReadsASingleFutureDay() = runBlocking {
        calendar.seed("Team standup", parseWhen("today at 2pm"))
        calendar.seed("Visa interview", parseWhen("in 14 days at 10am"))
        val result = tools.execute("get_calendar_events", """{"from":"in 14 days"}""")
        assertTrue(result.ok)
        val payload = JSONObject(result.payloadJson)
        assertEquals(0, payload.getJSONArray("google").length())
        assertEquals(1, payload.getJSONArray("system").length())
        val event = payload.getJSONArray("system").getJSONObject(0)
        assertTrue(event.getString("title").contains("visa", ignoreCase = true))
        assertTrue(event.getString("when").contains("10:00 AM"))
    }

    @Test
    fun getCalendarEventsReadsAnAfternoonSlice() = runBlocking {
        calendar.seed("Morning standup", parseWhen("today at 9am"))
        calendar.seed("Design review", parseWhen("today at 4pm"))
        val result = tools.execute(
            "get_calendar_events",
            """{"from":"today at 3pm","to":"today at 6pm"}""",
        )
        assertTrue(result.ok)
        val system = JSONObject(result.payloadJson).getJSONArray("system")
        assertEquals(1, system.length())
        assertTrue(system.getJSONObject(0).getString("title").contains("design", ignoreCase = true))
    }

    @Test
    fun getCalendarEventsReturnsPhoneAndGoogle() = runBlocking {
        calendar.seed("Team standup", parseWhen("today at 2pm"))
        google.granted = true
        google.seed("Design review", parseWhen("today at 4pm"))
        val result = tools.execute("get_calendar_events", """{"days":7}""")
        assertTrue(result.ok)
        val payload = JSONObject(result.payloadJson)
        assertTrue(payload.getBoolean("googleConnected"))
        assertEquals(1, payload.getJSONArray("system").length())
        assertEquals(1, payload.getJSONArray("google").length())
        assertTrue(payload.getJSONArray("system").getJSONObject(0).getString("title").contains("standup", ignoreCase = true))
        assertTrue(payload.getJSONArray("google").getJSONObject(0).getString("title").contains("design", ignoreCase = true))
    }

    @Test
    fun overlappingEventStartingYesterdayAppearsToday() = runBlocking {
        val start = parseWhen("yesterday")
        val end = parseWhen("today at 11pm")
        calendar.seed("Overnight on-call", start, end)
        val from = parseWhen("today")
        val to = from + 7L * 24 * 60 * 60 * 1000
        val system = SystemCalendar(repo, calendar, settings)
        val events = system.mirror(from, to)
        assertEquals(1, events.size)
        assertEquals("Overnight on-call", events.single().title)
    }

    @Test
    fun skipsGoogleSyncedDeviceEventsWhenGoogleApiIsLive() = runBlocking {
        calendar.seed("Synced standup", parseWhen("today at 2pm"), accountType = CalendarWindows.GOOGLE_ACCOUNT_TYPE)
        calendar.seed("Phone dentist", parseWhen("today at 3pm"), accountType = "LOCAL")
        google.granted = true
        google.seed("Cloud review", parseWhen("today at 4pm"))
        val from = parseWhen("today")
        val to = from + 7L * 24 * 60 * 60 * 1000
        val system = SystemCalendar(repo, calendar, settings)
        val device = system.mirror(from, to, excludeGoogleAccounts = true)
        assertEquals(listOf("Phone dentist"), device.map { it.title })
        val cloud = googleCalendar.mirror(from, to)
        assertEquals(listOf("Cloud review"), cloud.map { it.title })
    }

    @Test
    fun googleEventsAreReadOnly() = runBlocking {
        google.granted = true
        google.seed("Design review", parseWhen("today at 4pm"))
        contextBuilder.build()
        val stored = repo.calendarInRange(0, Long.MAX_VALUE).single { it.source == CalendarSource.GOOGLE }
        val update = tools.execute("update_calendar_event", """{"id":"${stored.id}","when":"today at 5pm"}""")
        assertTrue(!update.ok)
        assertTrue(update.payloadJson.contains("read-only", ignoreCase = true))
        val delete = tools.execute("delete_calendar_event", """{"id":"${stored.id}","confirmed":true}""")
        assertTrue(!delete.ok)
        assertTrue(delete.payloadJson.contains("read-only", ignoreCase = true))
    }

    private fun parseWhen(phrase: String): Long {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        return RelativeTimeParser.parse(phrase, now)!!.toInstant().toEpochMilli()
    }
}
