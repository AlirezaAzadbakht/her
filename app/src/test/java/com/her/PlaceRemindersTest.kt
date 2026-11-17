package com.her

import android.app.Application
import android.database.sqlite.SQLiteDatabase
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
import com.her.notify.Notifier
import com.her.reminders.CurrentLocation
import com.her.reminders.FakeReminderAlarms
import com.her.reminders.ReminderDelivery
import com.her.reminders.rearmAll
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
class PlaceRemindersTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry
    private lateinit var delivery: ReminderDelivery
    private val alarms = FakeReminderAlarms()
    private val shown = mutableListOf<String>()
    private var problem: String? = null
    private val location = object : CurrentLocation {
        override fun problem() = this@PlaceRemindersTest.problem
        override fun backgroundAllowed() = true
        override suspend fun fix() = 35.7 to 51.4
    }

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        val settings = AppSettingsStore(context, "place_reminders_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()))
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), reminderAlarms = alarms, location = location)
        delivery = ReminderDelivery(repo, object : Notifier(context) {
            override fun showReminder(id: String, text: String) { shown += text }
        })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reminderSetBeforeThePlaceIsSavedArmsOnceItIs() = runBlocking {
        val set = JSONObject(tools.execute("set_reminder", """{"message":"Water the plants","place":"home"}""").payloadJson)
        assertFalse(set.getBoolean("firesOnArrival"))
        assertTrue(alarms.places.isEmpty())

        val saved = tools.execute("save_place", """{"name":"Home"}""")
        assertTrue(saved.payloadJson, saved.ok)
        assertEquals(1, JSONObject(saved.payloadJson).getInt("remindersWaiting"))
        assertEquals("Home", alarms.places[set.getString("id")])
        assertEquals(35.7, repo.placeNamed("home")!!.lat, 0.0)
    }

    @Test
    fun reminderForASavedPlaceArmsRightAway() = runBlocking {
        tools.execute("save_place", """{"name":"gym"}""")
        val set = JSONObject(tools.execute("set_reminder", """{"message":"Bring the towel","place":"Gym"}""").payloadJson)
        assertTrue(set.getBoolean("firesOnArrival"))
        assertEquals("gym", alarms.places[set.getString("id")])
    }

    @Test
    fun savingAPlaceFailsHonestlyWithoutLocation() = runBlocking {
        problem = "location_off"
        val result = tools.execute("save_place", """{"name":"home"}""")
        assertFalse(result.ok)
        assertEquals("location_off", JSONObject(result.payloadJson).getString("reason"))
        assertTrue(repo.places().isEmpty())
    }

    @Test
    fun locationOffRemovesEveryPlaceAlert() = runBlocking {
        tools.execute("save_place", """{"name":"home"}""")
        tools.execute("set_reminder", """{"message":"Water the plants","place":"home"}""")
        assertEquals(1, alarms.places.size)

        alarms.watched = false
        alarms.rearmAll(repo)
        assertTrue(alarms.places.isEmpty())

        alarms.watched = true
        alarms.rearmAll(repo)
        assertEquals(1, alarms.places.size)
    }

    @Test
    fun arrivingFiresOnceAndHonorsOnlyIfOpen() = runBlocking {
        val id = JSONObject(tools.execute("set_reminder", """{"message":"Water the plants","place":"home"}""").payloadJson).getString("id")
        assertTrue(delivery.firePlace(id))
        assertFalse(delivery.firePlace(id))
        assertEquals(listOf("Water the plants"), shown)
        assertEquals(ReminderStatus.FIRED, repo.getReminder(id)?.status)

        tools.execute("create_task", """{"title":"Buy milk"}""")
        val milk = JSONObject(tools.execute("set_reminder", """{"message":"Milk!","place":"store","onlyIfOpen":"Buy milk"}""").payloadJson).getString("id")
        tools.execute("update_task", """{"id":"Buy milk","status":"DONE"}""")
        assertFalse(delivery.firePlace(milk))
        assertEquals(ReminderStatus.SKIPPED, repo.getReminder(milk)?.status)
    }

    @Test
    fun migrationThirteenToFourteenAddsPlaces() {
        val name = "migration_13_${System.nanoTime()}.db"
        HerDatabase.build(context, name).apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE places"); it.version = 13
        }
        val migrated = HerDatabase.build(context, name)
        runBlocking { assertTrue(migrated.placeDao().all().isEmpty()) }
        migrated.close()
        context.deleteDatabase(name)
    }
}
