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
import com.her.domain.AgentRunType
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** From a real backup: tools said ok while dropping what they were told, and records piled up as copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class RecordsStayTidyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "tidy_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-11-07T08:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    @After fun close() = db.close()

    private suspend fun run(name: String, args: JSONObject) = tools.execute(name, args.toString())
    private suspend fun ok(name: String, args: JSONObject) = run(name, args).also { assertTrue(it.payloadJson, it.ok) }.let { JSONObject(it.payloadJson) }

    @Test fun anUnknownFieldIsAnErrorAndATaskCanMoveProject() = runBlocking {
        val task = ok("create_task", JSONObject().put("title", "Alerts in chat")).getString("id")
        ok("update_project", JSONObject().put("name", "Cham"))
        val ignored = run("update_task", JSONObject().put("id", task).put("projectName", "Cham"))
        assertFalse(ignored.ok)
        assertTrue(ignored.payloadJson, ignored.payloadJson.contains("projectName"))

        ok("update_task", JSONObject().put("id", task).put("relatedProjectId", "Cham"))
        assertEquals(repo.projects().single().id, repo.getTask(task)!!.relatedProjectId)
    }

    @Test fun aMadeUpMessageIdMeansTheMessageBeingAnswered() = runBlocking {
        val message = repo.newChatMessage(MessageRole.USER, "My exam is on Monday", MessageStatus.SENT).also { repo.saveMessage(it) }
        val saved = ok("remember", JSONObject().put("content", "Exam on Monday").put("sourceMessageId", "latest")).getString("id")
        assertEquals(message.id, repo.getShort(saved)?.sourceMessageId ?: repo.getLong(saved)?.sourceMessageId)

        tools.runType = AgentRunType.HOURLY
        val background = ok("remember", JSONObject().put("content", "Likes tea").put("sourceMessageId", "current")).getString("id")
        assertNull(repo.getShort(background)?.sourceMessageId ?: repo.getLong(background)?.sourceMessageId)
    }

    @Test fun theSameResponsibilityOrQuestionIsKeptOnce() = runBlocking {
        val first = ok("create_recurring_responsibility", JSONObject().put("title", "Take the antibiotic").put("cadence", "daily")).getString("id")
        val again = ok("create_recurring_responsibility", JSONObject().put("title", "Take the antibiotic course").put("cadence", "twice a day"))
        assertEquals(first, again.getString("id"))
        assertEquals(1, repo.responsibilities().size)

        val one = ok("create_task", JSONObject().put("title", "Toe test")).getString("id")
        val two = ok("create_task", JSONObject().put("title", "Doctor visit")).getString("id")
        val question = JSONObject().put("status", "OPEN").put("observation", "Still open").put("question", "Did the toe test result come back?")
        ok("save_wondering", JSONObject(question.toString()).put("sourceId", one))
        assertFalse(run("save_wondering", JSONObject(question.toString()).put("sourceId", two).put("question", "Did the toe test come back?")).ok)
    }

    @Test fun expiredShortTermMemoriesGoAndFailedBubblesLeaveTheHistory() = runBlocking {
        val id = ok("remember", JSONObject().put("content", "Visiting Hamadan this weekend").put("scope", "short_term")).getString("id")
        ok("update_memory", JSONObject().put("id", id).put("expiresAt", "now"))
        assertEquals(1, repo.expireShortTermMemories())
        assertTrue(repo.getShort(id)!!.deletedAt != null)
        assertEquals(0, repo.expireShortTermMemories())

        repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, "I couldn't reach the model.", MessageStatus.FAILED))
        repo.saveMessage(repo.newChatMessage(MessageRole.USER, "hi", MessageStatus.SENT))
        assertEquals(listOf("hi"), repo.recentMessages(10).map { it.content })
    }
}
