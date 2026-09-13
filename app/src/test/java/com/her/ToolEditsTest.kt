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
    fun routineWithAnUnexpectedSourceStillSaves() = runBlocking {
        val result = tools.execute("create_routine", """{"title":"Gym","schedule":"Mon and Wed at 7am","source":"guess"}""")
        assertTrue(result.payloadJson, result.ok)
        assertEquals(1, repo.routines().size)
    }

    @Test
    fun forgetEverythingAlsoArchivesTheirUnderstanding() = runBlocking {
        tools.execute("update_user_understanding", """{"facet":"help_style","content":"Wants short answers"}""")
        val confirmId = JSONObject(tools.execute("forget_memory", """{"everything":true}""").payloadJson).getString("confirmId")
        assertTrue(tools.execute("forget_memory", """{"everything":true,"confirmId":"$confirmId"}""").ok)
        assertTrue(repo.activeUserUnderstandings().isEmpty())
    }
}
