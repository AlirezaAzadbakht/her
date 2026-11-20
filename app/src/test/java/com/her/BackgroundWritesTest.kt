package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.Identity
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.agent.tools.USER_RECORD_WRITES
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.AgentRunType
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** From a real backup: background passes completed a task, rewrote reminders, and re-answered old messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class BackgroundWritesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "bgwrites_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-11-07T08:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val delivered = mutableListOf<String>()
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), { delivered += it })
    @After fun close() = db.close()

    private suspend fun run(name: String, args: JSONObject) = tools.execute(name, args.toString())

    @Test fun backgroundPassesLeaveTasksAndRemindersAlone() = runBlocking {
        val task = JSONObject(run("create_task", JSONObject().put("title", "Book the dentist")).payloadJson).getString("id")
        val reminder = JSONObject(run("set_reminder", JSONObject().put("message", "How are you today?").put("when", "tomorrow at 12pm").put("repeat", "DAILY")).payloadJson).getString("id")
        for (type in AgentRunType.entries - AgentRunType.CHAT) {
            tools.runType = type
            assertFalse(run("update_task", JSONObject().put("id", task).put("status", "DONE")).ok)
            assertFalse(run("update_reminder", JSONObject().put("id", reminder).put("repeat", "NONE")).ok)
            assertFalse(run("set_reminder", JSONObject().put("message", "How are you today?").put("when", "tomorrow at 12pm")).ok)
            assertTrue(tools.specsFor(type).none { it.name in USER_RECORD_WRITES })
        }
        assertEquals("OPEN", repo.tasks().single().status.name)
        assertEquals(1, repo.reminders().size)
        assertEquals("DAILY", repo.reminders().single().repeat.name)
        tools.runType = AgentRunType.CHAT
        assertTrue(run("update_task", JSONObject().put("id", task).put("status", "DONE")).ok)
    }

    @Test fun anHourlyPassDoesNotAnswerTheLastMessageAgain() = runBlocking {
        repo.saveMessage(repo.newChatMessage(MessageRole.USER, "I'm upset I overate.", MessageStatus.SENT))
        repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, "You don't need to blame yourself for overeating; it happened, and it's over.", MessageStatus.SENT))
        clock.advance(6 * 60 * 60 * 1000L)
        tools.runType = AgentRunType.HOURLY
        val result = JSONObject(run("send_user_message", JSONObject().put("content", "No need to blame yourself for the overeating; it happened and it's over.")).payloadJson)
        assertEquals(Identity.NO_NOTIFICATION, result.getString("decision"))
        assertTrue(delivered.isEmpty())
    }

    @Test fun leakedWorkingNotesAreDroppedFromAPersianMessage() {
        val leaked = "We need autonomous morning brief current context stale, need mention commitments.\n\nصبح بخیر. امروز برنامه‌ای در تقویمت نیست."
        assertEquals("صبح بخیر. امروز برنامه‌ای در تقویمت نیست.", AgentOrchestrator.dropLeadingReasoning(leaked))
        val english = "Good morning. Nothing is on today.\n\nHave a calm one."
        assertEquals(english, AgentOrchestrator.dropLeadingReasoning(english))
        val persian = "صبح بخیر.\n\n**AI Arsenal** at 10."
        assertEquals(persian, AgentOrchestrator.dropLeadingReasoning(persian))
    }
}
