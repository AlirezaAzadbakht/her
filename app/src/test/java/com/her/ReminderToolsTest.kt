package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import com.her.reminders.FakeAlarmClock
import com.her.reminders.FakeReminderAlarms
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ReminderToolsTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry
    private val alarms = FakeReminderAlarms()
    private val alarmClock = FakeAlarmClock()

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        val settings = AppSettingsStore(context, "reminder_tools_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()))
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        tools = ToolRegistry(
            repo,
            HybridRanker(repo),
            settings,
            CalendarDataSource(context),
            WebSearchClient(),
            reminderAlarms = alarms,
            alarmClock = alarmClock,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun clockReminderIsArmedForItsTime() = runBlocking {
        val result = tools.execute("set_reminder", """{"message":"Call mom","when":"2026-09-12T12:00:00Z"}""")
        assertTrue(result.payloadJson, result.ok)
        val id = JSONObject(result.payloadJson).getString("id")

        assertEquals(Instant.parse("2026-09-12T12:00:00Z").toEpochMilli(), alarms.armed[id])
        assertEquals(ReminderTrigger.TIME, repo.getReminder(id)?.trigger)
    }

    @Test
    fun exactlyOneTriggerIsRequired() = runBlocking {
        assertFalse(tools.execute("set_reminder", """{"message":"Call mom"}""").ok)
        assertFalse(tools.execute("set_reminder", """{"message":"Call mom","when":"2026-09-12T12:00:00Z","place":"office"}""").ok)
        assertTrue(repo.reminders().isEmpty())
    }

    @Test
    fun pastTimesAndDatesWithoutAClockAreRejected() = runBlocking {
        assertFalse(tools.execute("set_reminder", """{"message":"Call mom","when":"2026-09-12T09:00:00Z"}""").ok)
        val dateOnly = tools.execute("set_reminder", """{"message":"Call mom","when":"tomorrow"}""")
        assertFalse(dateOnly.ok)
        assertTrue(JSONObject(dateOnly.payloadJson).getString("error").contains("time of day"))
    }

    @Test
    fun personReminderFindsOrCreatesThePersonAndIsNotArmed() = runBlocking {
        val result = tools.execute("set_reminder", """{"message":"Ask about the apartment keys","personName":"Reza"}""")
        assertTrue(result.payloadJson, result.ok)
        val reminder = repo.getReminder(JSONObject(result.payloadJson).getString("id"))!!

        assertEquals(ReminderTrigger.PERSON, reminder.trigger)
        assertEquals("Reza", repo.getPerson(reminder.personId!!)?.name)
        assertTrue(alarms.armed.isEmpty())
    }

    @Test
    fun onlyIfOpenLinksTheTask() = runBlocking {
        val taskId = JSONObject(tools.execute("create_task", """{"title":"Send the invoice"}""").payloadJson).getString("id")
        val result = tools.execute(
            "set_reminder",
            """{"message":"The invoice is still not sent","when":"2026-09-17T09:00:00Z","onlyIfOpen":"Send the invoice"}""",
        )
        assertTrue(result.payloadJson, result.ok)
        val reminder = repo.getReminder(JSONObject(result.payloadJson).getString("id"))!!

        assertEquals("tasks", reminder.onlyIfEntityType)
        assertEquals(taskId, reminder.onlyIfEntityId)
    }

    @Test
    fun updateMovesTheReminderAndRearms() = runBlocking {
        val id = JSONObject(tools.execute("set_reminder", """{"message":"Call the bank","when":"2026-09-12T15:00:00Z"}""").payloadJson).getString("id")
        val result = tools.execute("update_reminder", """{"id":"$id","when":"2026-09-12T17:00:00Z"}""")

        assertTrue(result.payloadJson, result.ok)
        assertEquals(Instant.parse("2026-09-12T17:00:00Z").toEpochMilli(), alarms.armed[id])
        assertEquals(1, repo.reminders().size)
    }

    @Test
    fun undoCancelsAndDisarms() = runBlocking {
        val id = JSONObject(tools.execute("set_reminder", """{"message":"Call mom","when":"2026-09-12T12:00:00Z"}""").payloadJson).getString("id")

        assertTrue(tools.undo("reminders", id))
        assertTrue(alarms.armed.isEmpty())
        assertEquals(ReminderStatus.CANCELLED, repo.getReminder(id)?.status)
    }

    @Test
    fun alarmWithinADayIsSetOnTheClock() = runBlocking {
        val result = tools.execute("set_alarm", """{"time":"2026-09-13T06:00:00Z","label":"Wake up"}""")

        assertTrue(result.payloadJson, result.ok)
        assertEquals(listOf(FakeAlarmClock.Alarm(6, 0, "Wake up", emptyList())), alarmClock.alarms)
    }

    @Test
    fun oneOffAlarmBeyondADayIsRefusedButARepeatingOneIsNot() = runBlocking {
        assertFalse(tools.execute("set_alarm", """{"time":"2026-09-15T06:00:00Z"}""").ok)
        assertTrue(alarmClock.alarms.isEmpty())

        assertTrue(tools.execute("set_alarm", """{"time":"2026-09-15T06:00:00Z","days":["monday"]}""").ok)
        assertEquals(1, alarmClock.alarms.size)
    }
}
