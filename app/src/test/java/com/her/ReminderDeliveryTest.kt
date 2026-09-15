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
import com.her.domain.MessageRole
import com.her.domain.ReminderStatus
import com.her.notify.Notifier
import com.her.reminders.FakeReminderAlarms
import com.her.reminders.ReminderDelivery
import java.time.Instant
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
class ReminderDeliveryTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry
    private lateinit var delivery: ReminderDelivery
    private val clock = FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli())
    private val shown = mutableListOf<String>()

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        val settings = AppSettingsStore(context, "reminder_delivery_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, clock)
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), reminderAlarms = FakeReminderAlarms())
        val notifier = object : Notifier(context) {
            override fun showReminder(id: String, text: String) {
                shown += text
            }
        }
        delivery = ReminderDelivery(repo, notifier)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun dueReminderRingsAndIsSavedAsHerLatestMessage() = runBlocking {
        val id = set("""{"message":"Time to call mom.","when":"2026-09-12T12:00:00Z"}""")
        delivery.fireDue()
        assertTrue("not due yet", shown.isEmpty())

        clock.set(Instant.parse("2026-09-12T12:00:30Z").toEpochMilli())
        delivery.fireDue()

        assertEquals(listOf("Time to call mom."), shown)
        assertEquals(ReminderStatus.FIRED, repo.getReminder(id)?.status)
        assertEquals("Time to call mom.", repo.recentMessages(5).last { it.role == MessageRole.ASSISTANT }.content)
        delivery.fireDue()
        assertEquals("rings once", 1, shown.size)
    }

    @Test
    fun conditionalReminderIsSkippedOnceTheTaskIsDone() = runBlocking {
        tools.execute("create_task", """{"title":"Send the invoice"}""")
        val id = set("""{"message":"The invoice still isn't sent.","when":"2026-09-12T12:00:00Z","onlyIfOpen":"Send the invoice"}""")
        tools.execute("update_task", """{"id":"Send the invoice","status":"DONE"}""")

        clock.set(Instant.parse("2026-09-12T12:01:00Z").toEpochMilli())
        delivery.fireDue()

        assertTrue(shown.isEmpty())
        assertEquals(ReminderStatus.SKIPPED, repo.getReminder(id)?.status)
    }

    @Test
    fun repeatingReminderMovesToItsNextTime() = runBlocking {
        val id = set("""{"message":"Take out the trash.","when":"2026-09-13T20:00:00Z","repeat":"WEEKLY"}""")

        clock.set(Instant.parse("2026-09-13T20:05:00Z").toEpochMilli())
        delivery.fireDue()

        val reminder = repo.getReminder(id)!!
        assertEquals(listOf("Take out the trash."), shown)
        assertEquals(ReminderStatus.SCHEDULED, reminder.status)
        assertEquals(Instant.parse("2026-09-20T20:00:00Z").toEpochMilli(), reminder.fireAt)
    }

    @Test
    fun anotherDevicesReminderRingsThere() = runBlocking {
        val id = set("""{"message":"Water the plants.","when":"2026-09-12T12:00:00Z"}""")
        repo.saveReminder(repo.getReminder(id)!!.copy(deviceId = "other-device"))

        clock.set(Instant.parse("2026-09-12T12:01:00Z").toEpochMilli())
        delivery.fireDue()

        assertTrue(shown.isEmpty())
        assertEquals(ReminderStatus.SCHEDULED, repo.getReminder(id)?.status)
    }

    private suspend fun set(arguments: String): String {
        val result = tools.execute("set_reminder", arguments)
        assertTrue(result.payloadJson, result.ok)
        return JSONObject(result.payloadJson).getString("id")
    }
}
