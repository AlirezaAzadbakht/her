package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.core.newId
import com.her.data.db.HerDatabase
import com.her.data.google.GoogleTasksClient
import com.her.data.google.GoogleTasksSync
import com.her.data.google.RemoteTask
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.domain.TaskItem
import com.her.domain.TaskStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class GoogleTasksTest {
    private lateinit var db: HerDatabase
    private lateinit var settings: AppSettingsStore
    private lateinit var repo: HerRepository
    private val clock = FakeClock(Instant.parse("2026-09-24T09:00:00Z").toEpochMilli())
    private val google = FakeTasksClient()
    private lateinit var sync: GoogleTasksSync

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        settings = AppSettingsStore(context, "google_tasks_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, clock)
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        settings.update { it.copy(googleTasksEnabled = true) }
        sync = GoogleTasksSync(repo, google, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun clientFindsOrCreatesTheHerList() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"l0","title":"Groceries"},{"id":"l1","title":"Her"}]}"""))
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            server.enqueue(MockResponse().setBody("""{"id":"l2","title":"Her"}"""))
            val client = GoogleTasksClient(baseUrl = server.url("/tasks/v1").toString().trimEnd('/'))

            assertEquals("l1", client.herListId("tok"))
            assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
            assertEquals("l2", client.herListId("tok"))
            server.takeRequest()
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("Her", JSONObject(create.body.readUtf8()).getString("title"))
        }
    }

    @Test
    fun clientPagesThroughChangesAndReadsDatesAndStates() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"items":[{"id":"a","title":"Pay rent","status":"completed","due":"2026-09-25T00:00:00.000Z","updated":"2026-09-24T08:00:00.000Z"}],"nextPageToken":"p2"}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"b","title":"Old","deleted":true}]}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = GoogleTasksClient(baseUrl = server.url("/tasks/v1").toString().trimEnd('/'))

            val tasks = client.changedSince("tok", "l1", since = Instant.parse("2026-09-24T00:00:00Z").toEpochMilli())
            assertEquals(listOf("a", "b"), tasks.map { it.id })
            assertEquals(LocalDate.of(2026, 9, 25), tasks[0].due)
            assertTrue(tasks[0].completed && tasks[1].deleted)
            assertTrue(server.takeRequest().path!!.contains("updatedMin="))
            assertTrue(server.takeRequest().path!!.contains("pageToken=p2"))

            client.delete("tok", "l1", "gone")
        }
    }

    @Test
    fun nothingHappensUntilItIsTurnedOn() = runBlocking {
        settings.update { it.copy(googleTasksEnabled = false) }
        repo.saveTask(task("Renew passport"))
        sync.sync()
        assertTrue(google.remote.isEmpty())
    }

    @Test
    fun aNewTaskIsSentAndRemembersItsGoogleId() = runBlocking {
        repo.saveTask(task("Renew passport", dueAt = Instant.parse("2026-09-26T15:00:00Z").toEpochMilli()))
        repo.saveTask(task("Someone else's", deviceId = "other-phone"))
        sync.sync()

        assertEquals(listOf("Renew passport"), google.remote.values.map { it.title })
        assertEquals(LocalDate.of(2026, 9, 26), google.remote.values.single().due)
        assertEquals("g1", repo.tasks().single { it.title == "Renew passport" }.googleTaskId)
    }

    @Test
    fun finishingItInGoogleFinishesItHere() = runBlocking {
        repo.saveTask(task("Renew passport"))
        sync.sync()
        clock.advance(60_000)
        google.remote["g1"] = google.remote.getValue("g1").copy(completed = true, updatedAt = clock.peek())
        clock.advance(1_000)
        sync.sync()

        assertEquals(TaskStatus.DONE, repo.taskByGoogleId("g1")?.status)
    }

    @Test
    fun aTaskAddedInGoogleArrivesOnceDueAtTheEndOfItsDay() = runBlocking {
        google.remote["x1"] = RemoteTask("x1", "Call the bank", null, LocalDate.of(2026, 9, 25), completed = false, updatedAt = clock.peek())
        sync.sync()
        settings.update { it.copy(lastTasksSyncAt = 0) }
        sync.sync()

        val arrived = repo.tasks().filter { it.title == "Call the bank" }
        assertEquals(1, arrived.size)
        assertEquals("gtask-x1", arrived.single().id)
        assertEquals(Instant.parse("2026-09-25T23:59:00Z").toEpochMilli(), arrived.single().dueAt)
    }

    @Test
    fun droppingItHereDeletesItInGoogle() = runBlocking {
        repo.saveTask(task("Renew passport"))
        sync.sync()
        val sent = repo.taskByGoogleId("g1")!!
        clock.advance(1_000)
        repo.saveTask(sent.copy(status = TaskStatus.DROPPED, updatedAt = clock.peek(), version = sent.version + 1))
        sync.sync()

        assertEquals(listOf("g1"), google.deleted)
    }

    private fun task(title: String, dueAt: Long? = null, deviceId: String = repo.deviceId): TaskItem {
        val now = clock.peek()
        return TaskItem(newId(), title, null, TaskStatus.OPEN, dueAt, null, null, now, now, deviceId, 1, null)
    }

    /** Google Tasks in memory: one list, ids g1, g2, … for what she sends. */
    private class FakeTasksClient : GoogleTasksClient() {
        val remote = linkedMapOf<String, RemoteTask>()
        val deleted = mutableListOf<String>()
        private var next = 0

        override suspend fun token() = "tok"

        override fun herListId(token: String) = "her"

        override fun changedSince(token: String, listId: String, since: Long) =
            remote.values.filter { since == 0L || it.updatedAt > since }

        override fun insert(token: String, listId: String, task: RemoteTask): String {
            val id = "g${++next}"
            remote[id] = task.copy(id = id)
            return id
        }

        override fun patch(token: String, listId: String, id: String, task: RemoteTask) {
            remote[id] = task.copy(id = id, updatedAt = remote[id]?.updatedAt ?: 0L)
        }

        override fun delete(token: String, listId: String, id: String) {
            deleted += id
            remote.remove(id)
        }
    }
}
