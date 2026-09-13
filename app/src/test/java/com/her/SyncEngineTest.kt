package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.drive.ChangeLogName
import com.her.data.drive.DriveClient
import com.her.data.drive.SyncEngine
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import com.her.domain.GroceryStatus
import com.her.domain.TaskStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val clock = FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli())
    private val drive = FakeDrive()
    private val a = Device("a")
    private val b = Device("b")

    @After
    fun tearDown() {
        a.db.close()
        b.db.close()
    }

    @Test
    fun recordsReachTheOtherDeviceWithoutEchoing() = runBlocking {
        val task = a.call("create_task", """{"title":"Renew passport","dueAt":"1790000000000"}""")
        val memory = a.call("remember", """{"content":"The living room lamp is 15 watts","scope":"long_term"}""")
        val person = a.call("update_person", """{"name":"Sara","relationship":"colleague"}""")
        val event = a.call("create_calendar_event", """{"title":"Dentist","when":"1790000000000"}""")
        a.call("update_user_profile", """{"userName":"Alireza"}""")
        a.repo.saveCalendarEvent(mirroredDeviceEvent("sys1"))
        a.engine.sync("token")

        // A second file from the same device; the fake lists newest first to prove order does not matter.
        val laterTask = a.call("create_task", """{"title":"Call the bank"}""")
        a.engine.sync("token")

        val summary = b.engine.sync("token")

        assertTrue(summary.applied > 0)
        assertEquals("Renew passport", b.repo.getTask(task)?.title)
        assertEquals(1790000000000L, b.repo.getTask(task)?.dueAt)
        assertEquals("Call the bank", b.repo.getTask(laterTask)?.title)
        assertTrue(b.repo.activeLong().any { it.id == memory })
        assertEquals("colleague", b.repo.getPerson(person)?.relationship)
        assertEquals("Alireza", b.repo.getProfile().userName)
        assertNotNull(b.repo.getCalendarEvent(event))
        assertNull("device calendar mirrors stay on their own phone", b.repo.getCalendarEvent("sys1"))
        assertTrue("applied rows must not be queued for upload again", b.repo.pendingSyncOps().isEmpty())
        assertEquals(0, b.engine.sync("token").applied)
    }

    @Test
    fun editsAndDeletesFlowBack() = runBlocking {
        val task = a.call("create_task", """{"title":"Fix the lamp"}""")
        val grocery = a.call("add_grocery", """{"name":"rice"}""")
        a.engine.sync("token")
        b.engine.sync("token")

        clock.advance(60_000)
        b.call("update_task", """{"id":"$task","status":"DONE"}""")
        b.call("remove_grocery", """{"id":"$grocery"}""")
        b.engine.sync("token")
        a.engine.sync("token")

        assertEquals(TaskStatus.DONE, a.repo.getTask(task)?.status)
        assertEquals(GroceryStatus.DROPPED, a.repo.getGrocery(grocery)?.status)
        assertNotNull(a.repo.getGrocery(grocery)?.deletedAt)
    }

    @Test
    fun changeLogNamesParse() {
        assertEquals(
            ChangeLogName("6f1c-44aa", 1, 20),
            ChangeLogName.parse("6f1c-44aa/1-20.ndjson"),
        )
        assertNull(ChangeLogName.parse("notes.txt"))
        assertNull(ChangeLogName.parse("device/1-x.ndjson"))
    }

    private fun mirroredDeviceEvent(id: String): CalendarEvent {
        val now = clock.nowMillis()
        return CalendarEvent(
            id = id,
            title = "Team standup",
            startAt = now + 3_600_000,
            endAt = now + 5_400_000,
            location = null,
            notes = null,
            externalId = "instance-1",
            source = CalendarSource.SYSTEM,
            calendarId = "1",
            createdAt = now,
            updatedAt = now,
            deviceId = a.repo.deviceId,
            version = 1,
            deletedAt = null,
        )
    }

    private inner class Device(name: String) {
        val db: HerDatabase = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        private val settings = AppSettingsStore(context, "sync_${name}_${System.nanoTime()}")
        val repo = HerRepository(db, settings, clock)
        private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
        val engine = SyncEngine(repo, drive)

        suspend fun call(tool: String, arguments: String): String {
            val result = tools.execute(tool, arguments)
            check(result.ok) { "$tool failed: ${result.payloadJson}" }
            return JSONObject(result.payloadJson).optString("id")
        }
    }

    private class FakeDrive : DriveClient() {
        private val names = linkedMapOf<String, String>()
        private val contents = mutableMapOf<String, String>()

        override fun listAppData(token: String): List<DriveFile> =
            names.entries.reversed().map { DriveFile(it.key, it.value) }

        override fun download(token: String, fileId: String): String = contents.getValue(fileId)

        override fun uploadNdjson(token: String, name: String, content: String): String {
            val id = "file-${names.size}"
            names[id] = name
            contents[id] = content
            return id
        }
    }
}
