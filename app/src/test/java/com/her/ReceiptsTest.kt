package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.runner.Receipt
import com.her.agent.runner.Receipts
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.GroceryStatus
import com.her.domain.TaskStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ReceiptsTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        val settings = AppSettingsStore(context, "receipts_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()))
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun newGroceryIsUndoableButARepeatIsNot() {
        val fresh = Receipts.describe("add_grocery", """{"name":"rice"}""", """{"ok":true,"id":"g1","created":true}""")!!
        assertEquals("groceries", fresh.entityType)
        assertEquals("added rice to groceries", fresh.label)
        assertTrue(fresh.undoable)
        val repeat = Receipts.describe("add_grocery", """{"name":"rice"}""", """{"ok":true,"id":"g1","created":false}""")!!
        assertFalse(repeat.undoable)
    }

    @Test
    fun privateBookkeepingIsNotAnnounced() {
        assertNull(Receipts.describe("update_agent_state", """{"kind":"note","content":"x"}""", """{"ok":true,"id":"s1"}"""))
        assertNull(Receipts.describe("update_user_understanding", """{"facet":"help_style"}""", """{"ok":true,"id":"u1"}"""))
        assertNull(Receipts.describe("search_memory", """{"query":"x"}""", """{"ok":true}"""))
    }

    @Test
    fun longTermMemoryPointsAtTheRightTable() {
        val receipt = Receipts.describe("remember", """{"content":"Sara is allergic to nuts"}""", """{"ok":true,"id":"m1","scope":"long_term"}""")!!
        assertEquals("long_term_memories", receipt.entityType)
    }

    @Test
    fun metadataRoundTripsDedupesAndMarksUndone() {
        val a = Receipt("add_grocery", "added rice to groceries", "groceries", "g1", undoable = true)
        val b = Receipt("update_grocery", "updated groceries", "groceries", "g2", undoable = false)
        val metadata = Receipts.toMetadata(listOf(a, a, b, b.copy(entityId = "g3")))!!
        assertEquals(listOf(a, b), Receipts.fromMetadata(metadata))
        assertEquals(listOf(a.copy(undone = true), b), Receipts.fromMetadata(Receipts.markUndone(metadata, listOf(a))))
        assertNull(Receipts.toMetadata(emptyList()))
    }

    @Test
    fun undoDropsWhatSheJustCreated() = runBlocking {
        val grocery = JSONObject(tools.execute("add_grocery", """{"name":"eggs"}""").payloadJson).getString("id")
        val task = JSONObject(tools.execute("create_task", """{"title":"Fix the lamp"}""").payloadJson).getString("id")
        val memory = JSONObject(tools.execute("remember", """{"content":"The lamp is 15 watts","scope":"long_term"}""").payloadJson).getString("id")
        val event = JSONObject(tools.execute("create_calendar_event", """{"title":"Dentist","when":"1790000000000"}""").payloadJson).getString("id")

        assertTrue(tools.undo("groceries", grocery))
        assertTrue(tools.undo("tasks", task))
        assertTrue(tools.undo("long_term_memories", memory))
        assertTrue(tools.undo("calendar_events", event))

        assertEquals(GroceryStatus.DROPPED, repo.getGrocery(grocery)?.status)
        assertEquals(TaskStatus.DROPPED, repo.getTask(task)?.status)
        assertTrue(repo.activeLong().none { it.id == memory })
        assertNotNull(repo.getCalendarEvent(event)?.deletedAt)
        assertFalse("a second undo has nothing left to do", tools.undo("tasks", task))
        assertFalse(tools.undo("people", "p1"))
    }
}
