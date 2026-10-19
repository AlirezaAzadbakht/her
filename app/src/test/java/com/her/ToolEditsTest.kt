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
import com.her.domain.ConfirmationKind
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.PendingConfirmation
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
class ToolEditsTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        val settings = AppSettingsStore(context, "tool_edits_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()))
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun statusFieldsListTheirAllowedValues() {
        val spec = tools.specs().first { it.name == "update_task" }
        val values = JSONObject(spec.parametersJson).getJSONObject("properties").getJSONObject("status").getJSONArray("enum")
        assertEquals(setOf("OPEN", "DONE", "DROPPED"), (0 until values.length()).map { values.getString(it) }.toSet())
    }

    @Test
    fun projectSearchListsForBlankOrMissingQueryAndKeepsDeletedProjectsHidden() = runBlocking {
        val first = tools.execute("update_project", """{"name":"Application","importance":0.9}""")
        val second = tools.execute("update_project", """{"name":"Garden","importance":0.2}""")
        val removed = tools.execute("update_project", """{"name":"Old project"}""")
        assertTrue(first.payloadJson, first.ok)
        assertTrue(second.payloadJson, second.ok)
        assertTrue(removed.payloadJson, removed.ok)
        val deleted = repo.getProject(JSONObject(removed.payloadJson).getString("id"))!!
        repo.saveProject(deleted.copy(deletedAt = repo.clock.nowMillis()))
        for (args in listOf("{}", """{"query":""}""", """{"query":"   "}""", """{"query":null}""")) {
            val result = tools.execute("search_projects", args)
            assertTrue(result.payloadJson, result.ok)
            val projects = JSONObject(result.payloadJson).getJSONArray("projects")
            assertEquals(listOf("Application", "Garden"), (0 until projects.length()).map { projects.getJSONObject(it).getString("name") })
        }
    }

    @Test
    fun projectSearchStillFiltersAndRejectsWrongTypes() = runBlocking {
        assertTrue(tools.execute("update_project", """{"name":"Application"}""").ok)
        assertTrue(tools.execute("update_project", """{"name":"Garden"}""").ok)
        val result = tools.execute("search_projects", """{"query":" Application "}""")
        assertTrue(result.payloadJson, result.ok)
        val projects = JSONObject(result.payloadJson).getJSONArray("projects")
        assertEquals(1, projects.length())
        assertEquals("Application", projects.getJSONObject(0).getString("name"))
        val empty = tools.execute("search_projects", """{"query":"unmatched"}""")
        assertTrue(empty.payloadJson, empty.ok)
        assertEquals(0, JSONObject(empty.payloadJson).getJSONArray("projects").length())
        assertFalse(tools.execute("search_projects", """{"query":42}""").ok)
    }

    @Test
    fun nightlyProjectListingWorksWhenThereAreNoProjects() = runBlocking {
        tools.runType = com.her.domain.AgentRunType.NIGHTLY
        val result = tools.execute("search_projects", """{"query":""}""")
        assertTrue(result.payloadJson, result.ok)
        assertEquals(0, JSONObject(result.payloadJson).getJSONArray("projects").length())
    }

    @Test
    fun updateTaskMovesTheDeadline() = runBlocking {
        val id = JSONObject(tools.execute("create_task", """{"title":"Renew passport","dueAt":"1790000000000"}""").payloadJson).getString("id")
        val result = tools.execute("update_task", """{"id":"Renew passport","dueAt":"1790086400000"}""")
        assertTrue(result.payloadJson, result.ok)
        assertEquals(1790086400000L, repo.getTask(id)?.dueAt)
    }

    @Test
    fun updateTaskRejectsATimeItCannotRead() = runBlocking {
        tools.execute("create_task", """{"title":"Call the bank"}""")
        assertFalse(tools.execute("update_task", """{"id":"Call the bank","dueAt":"blorp"}""").ok)
    }

    @Test
    fun updateCommitmentByTitleRenegotiatesTheDeadline() = runBlocking {
        val id = JSONObject(
            tools.execute("create_commitment", """{"title":"Send Ali the report","dueAt":"1790000000000","promisedTo":"Ali"}""").payloadJson,
        ).getString("id")
        val result = tools.execute("update_commitment", """{"id":"Send Ali the report","dueAt":"1790172800000"}""")
        assertTrue(result.payloadJson, result.ok)
        assertEquals(1790172800000L, repo.getCommitment(id)?.dueAt)
        assertEquals("Ali", repo.getCommitment(id)?.promisedTo)
    }

    @Test
    fun unknownCommitmentIsAnError() = runBlocking {
        assertFalse(tools.execute("update_commitment", """{"id":"nope","status":"DONE"}""").ok)
    }

    @Test
    fun routineWithAnUnexpectedSourceIsRejected() = runBlocking {
        val result = tools.execute("create_routine", """{"title":"Gym","schedule":"Mon and Wed at 7am","source":"guess"}""")
        assertFalse(result.ok)
        assertTrue(repo.routines().isEmpty())
    }

    @Test
    fun statusAliasesStillPassEnumChecks() = runBlocking {
        tools.execute("create_task", """{"title":"Book flights"}""")
        val result = tools.execute("update_task", """{"id":"Book flights","status":"cancelled"}""")
        assertTrue(result.payloadJson, result.ok)
        assertTrue(repo.tasks().none { it.title.contains("flight", ignoreCase = true) })
    }

    @Test
    fun forgetEverythingAlsoArchivesTheirUnderstanding() = runBlocking {
        tools.execute("update_user_understanding", """{"facet":"help_style","content":"Wants short answers"}""")
        val confirmId = JSONObject(tools.execute("forget_memory", """{"everything":true}""").payloadJson).getString("confirmId")
        assertFalse("same-turn confirmId must not wipe", tools.execute("forget_memory", """{"everything":true,"confirmId":"$confirmId"}""").ok)
        theyConfirm()
        assertTrue(tools.execute("forget_memory", """{"everything":true,"confirmId":"$confirmId"}""").ok)
        assertTrue(repo.activeUserUnderstandings().isEmpty())
    }

    @Test
    fun forgetEverythingAlsoRemovesHistoricalMemories() = runBlocking {
        val id = rememberLong("Lived in Tehran")
        tools.execute("update_memory", """{"id":"$id","status":"HISTORICAL"}""")
        val confirmId = JSONObject(tools.execute("forget_memory", """{"everything":true}""").payloadJson).getString("confirmId")
        theyConfirm()
        assertTrue(tools.execute("forget_memory", """{"everything":true,"confirmId":"$confirmId"}""").ok)
        assertTrue(repo.getLong(id)!!.deletedAt != null)
    }

    @Test
    fun forgetEverythingRejectsAnotherKindOfConfirmation() = runBlocking {
        val id = rememberLong("Lives in Isfahan")
        repo.saveConfirmation(PendingConfirmation("c1", ConfirmationKind.CALENDAR_DELETE, "Delete event", "{}", 0))
        assertFalse(tools.execute("forget_memory", """{"everything":true,"confirmId":"c1"}""").ok)
        assertEquals(null, repo.getLong(id)!!.deletedAt)
    }

    @Test
    fun wrongArgumentTypesAreRejected() = runBlocking {
        assertFalse(tools.execute("remember", """{"content":42,"scope":"long_term"}""").ok)
        assertFalse(tools.execute("create_task", """{"title":true}""").ok)
    }

    @Test
    fun forgettingAnUnknownMemoryIsAnError() = runBlocking {
        assertFalse(tools.execute("forget_memory", """{"id":"nope"}""").ok)
    }

    @Test
    fun updateMemoryClampsScores() = runBlocking {
        val id = rememberLong("Likes tea")
        tools.execute("update_memory", """{"id":"$id","confidence":5,"importance":-1}""")
        assertEquals(1.0, repo.getLong(id)!!.confidence, 0.0)
        assertEquals(0.0, repo.getLong(id)!!.importance, 0.0)
    }

    private suspend fun rememberLong(content: String): String =
        JSONObject(tools.execute("remember", """{"content":"$content","scope":"long_term"}""").payloadJson).getString("id")

    private suspend fun theyConfirm() {
        repo.saveMessage(repo.newChatMessage(MessageRole.USER, "yes, do it", MessageStatus.SENT))
    }
}
