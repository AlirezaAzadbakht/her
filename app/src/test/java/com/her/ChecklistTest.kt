package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
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
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ChecklistTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dbName = "checklist_${System.nanoTime()}.db"
    private val clock = FakeClock(Instant.parse("2026-11-23T10:00:00Z").toEpochMilli())
    private val settings = AppSettingsStore(context, "checklist_${System.nanoTime()}")
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry

    @Before
    fun setup() = open()

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun open() {
        db = Room.databaseBuilder(context, HerDatabase::class.java, dbName).allowMainThreadQueries().build()
        repo = HerRepository(db, settings, clock)
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    }

    private suspend fun call(name: String, args: String): JSONObject {
        val result = tools.execute(name, args)
        assertTrue("$name $args -> ${result.payloadJson}", result.ok)
        return JSONObject(result.payloadJson)
    }

    private suspend fun items(list: String) = repo.observeList(list).first()
    private suspend fun names(list: String) = items(list).map { it.name }
    private suspend fun id(list: String, name: String) = items(list).single { it.name == name }.id
    private suspend fun room() = call("add_list_items", """{"list":"Room","items":["Put clothes in the basket","Clear the desk","Take out the trash"]}""")

    @Test
    fun aChecklistKeepsItsOrderAndTicksAcrossARestart() = runBlocking {
        room()
        repo.setListItemDone(id("room", "Clear the desk"), true)
        db.close()
        open()
        assertEquals(listOf("Put clothes in the basket", "Clear the desk", "Take out the trash"), names("room"))
        assertEquals(listOf(GroceryStatus.ACTIVE, GroceryStatus.PURCHASED, GroceryStatus.ACTIVE), items("room").map { it.status })
    }

    @Test
    fun badChangesAreRefusedAndChangeNothing() = runBlocking {
        room()
        call("add_list_items", """{"list":"trip","items":["Passport"]}""")
        val passport = id("trip", "Passport")
        val before = items("room") + items("trip")
        assertFalse(tools.execute("update_grocery", """{"id":"nope","status":"PURCHASED"}""").ok)
        assertFalse("an item on another list", tools.execute("remove_grocery", """{"id":"$passport","list":"room"}""").ok)
        assertFalse("breaking down another list's item", tools.execute("add_list_items", """{"list":"room","items":["x"],"replace":"$passport"}""").ok)
        assertFalse(tools.execute("add_list_items", """{"list":"room","items":["  "]}""").ok)
        assertFalse(tools.execute("add_list_items", """{"list":"groceries","items":["milk"]}""").ok)
        assertFalse(tools.execute("add_list_items", """{"list":"room","items":["${"x".repeat(201)}"]}""").ok)
        assertEquals(before, items("room") + items("trip"))
    }

    @Test
    fun breakingAStepDownPutsTheStepsInItsPlace() = runBlocking {
        room()
        call("update_grocery", """{"id":"${id("room", "Put clothes in the basket")}","status":"PURCHASED"}""")
        val trip = call("add_list_items", """{"list":"trip","items":["Passport"]}""")
        val tripBefore = items("trip")
        val desk = id("room", "Clear the desk")
        call("add_list_items", """{"list":"room","items":["Take cups to the kitchen","Throw away desk rubbish","Put loose things in one pile"],"replace":"$desk"}""")
        assertEquals(
            listOf("Put clothes in the basket", "Take cups to the kitchen", "Throw away desk rubbish", "Put loose things in one pile", "Take out the trash"),
            names("room"),
        )
        assertEquals(GroceryStatus.PURCHASED, items("room").first().status)
        assertEquals(GroceryStatus.DROPPED, repo.getGrocery(desk)?.status)
        assertEquals("another list is untouched", tripBefore, items("trip"))
        assertEquals("trip", trip.getString("list"))
    }

    @Test
    fun retriesDoNotDuplicateOrReapply() = runBlocking {
        room()
        val again = room()
        assertEquals(3, again.getJSONArray("skipped").length())
        assertEquals(3, items("room").size)
        val desk = id("room", "Clear the desk")
        val breakdown = """{"list":"room","items":["Cups","Rubbish"],"replace":"$desk"}"""
        call("add_list_items", breakdown)
        assertTrue(call("add_list_items", breakdown).getBoolean("alreadyReplaced"))
        assertEquals(listOf("Put clothes in the basket", "Cups", "Rubbish", "Take out the trash"), names("room"))
    }

    @Test
    fun undoPutsBackHerTurnButKeepsLaterTaps() = runBlocking {
        room()
        val clothes = id("room", "Put clothes in the basket")
        val trash = id("room", "Take out the trash")
        // One turn: "Clothes are done, and I should change the sheets."
        val tick = """{"id":"$clothes","status":"PURCHASED"}"""
        val add = """{"list":"room","items":["Change the sheets"]}"""
        val receipts = listOf(
            Receipts.describe("update_grocery", tick, call("update_grocery", tick).toString())!!,
            Receipts.describe("add_list_items", add, call("add_list_items", add).toString())!!,
        )
        val merged = Receipts.fromMetadata(Receipts.toMetadata(receipts, listOf("room"))).single()
        assertEquals(Receipts.LISTS, merged.entityType)
        assertTrue(merged.undoable)
        repo.setListItemDone(trash, true)

        assertTrue(tools.undoList(merged.undo))
        assertEquals(listOf("Put clothes in the basket", "Clear the desk", "Take out the trash"), names("room"))
        assertEquals(GroceryStatus.ACTIVE, repo.getGrocery(clothes)?.status)
        assertEquals("their later tap stays", GroceryStatus.PURCHASED, repo.getGrocery(trash)?.status)
    }

    @Test
    fun undoNeverOverwritesAnItemTheyChangedSince() = runBlocking {
        room()
        val desk = id("room", "Clear the desk")
        val renamed = call("update_grocery", """{"id":"$desk","name":"Clear the desk top"}""")
        repo.setListItemDone(desk, true)
        assertFalse(tools.undoList(renamed.getJSONArray("undo").toString()))
        assertEquals("Clear the desk top", repo.getGrocery(desk)?.name)
        assertEquals(GroceryStatus.PURCHASED, repo.getGrocery(desk)?.status)
    }

    @Test
    fun aTapDuringHerTurnWinsOverHerStaleUpdate() = runBlocking {
        room()
        val desk = id("room", "Clear the desk")
        tools.turnStartedAt = clock.peek()
        repo.setListItemDone(desk, true)
        val stale = tools.execute("update_grocery", """{"id":"$desk","status":"ACTIVE"}""")
        assertFalse(stale.ok)
        assertTrue(stale.payloadJson.contains("by hand"))
        assertEquals(GroceryStatus.PURCHASED, repo.getGrocery(desk)?.status)
        tools.turnStartedAt = Long.MAX_VALUE
        assertTrue("on a later turn she may change it", tools.execute("update_grocery", """{"id":"$desk","status":"ACTIVE"}""").ok)
    }

    @Test
    fun herNextTurnSeesTheirTicksAndNotes() = runBlocking {
        room()
        repo.setListItemDone(id("room", "Clear the desk"), true)
        call("update_grocery", """{"id":"${id("room", "Take out the trash")}","notes":"leave alone"}""")
        val bundle = ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context)).build("hi").systemBundle
        val clothes = bundle.indexOf("[ ] Put clothes in the basket")
        val desk = bundle.indexOf("[x] Clear the desk")
        assertTrue(bundle, clothes in 0 until desk)
        assertTrue(bundle, bundle.contains("[ ] Take out the trash (note: leave alone)"))
    }

    @Test
    fun theRepliesThatShowAListSayWhichOne() {
        val metadata = Receipts.toMetadata(emptyList(), listOf("room", "room", "trip"))
        assertEquals(listOf("room", "trip"), Receipts.listsOf(metadata))
        assertNull(Receipts.toMetadata(emptyList(), emptyList()))
    }

    @Test
    fun migrationFromFourteenKeepsItemsAndOrdersByAge() = runBlocking {
        db.close()
        context.deleteDatabase(dbName)
        Room.databaseBuilder(context, HerDatabase::class.java, dbName).build().apply { openHelper.writableDatabase; close() }
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE groceries")
            it.execSQL("""CREATE TABLE `groceries` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity` TEXT, `category` TEXT,
                `status` TEXT NOT NULL, `reason` TEXT, `recurrenceScore` REAL NOT NULL, `notes` TEXT, `store` TEXT,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `version` INTEGER NOT NULL,
                `deletedAt` INTEGER, `list` TEXT NOT NULL DEFAULT 'groceries', PRIMARY KEY(`id`))""")
            it.execSQL("INSERT INTO groceries VALUES ('p2','socks',NULL,NULL,'ACTIVE',NULL,0.0,NULL,NULL,2,2,'dev',1,NULL,'packing')")
            it.execSQL("INSERT INTO groceries VALUES ('p1','passport',NULL,NULL,'PURCHASED',NULL,0.0,NULL,NULL,1,1,'dev',1,NULL,'packing')")
            it.version = 14
        }
        db = Room.databaseBuilder(context, HerDatabase::class.java, dbName).addMigrations(HerDatabase.MIGRATION_14_15).allowMainThreadQueries().build()
        repo = HerRepository(db, settings, clock)
        assertEquals(listOf("passport", "socks"), repo.observeList("packing").first().map { it.name })
        assertNull(repo.getGrocery("p1")?.userEditedAt)
    }
}
