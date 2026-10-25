package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.companion.*
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.*
import com.her.data.drive.SyncCodec
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.*
import com.her.notify.Notifier
import com.her.reminders.ReminderDelivery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WonderingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "wondering_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-10-17T10:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val delivered = mutableListOf<String>()
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), { delivered += it })
    @After fun close() = db.close()
    private suspend fun call(name: String, args: JSONObject): JSONObject {
        val result = tools.execute(name, args.toString())
        assertTrue(result.payloadJson, result.ok)
        return JSONObject(result.payloadJson)
    }
    private suspend fun task() = call("create_task", JSONObject().put("title", "Send draft")).getString("id")
    private suspend fun wonder(source: String) = call("save_wondering", JSONObject().put("sourceId", source).put("status", "OPEN")
        .put("observation", "The draft is still open").put("question", "Is anything blocking the draft?"))

    @Test fun questionsAreOccasionalAndNeverRepeatedOrSentAtNight() = runBlocking {
        val source = task()
        val id = wonder(source).getString("id")
        val ask = JSONObject().put("id", id).put("expectedVersion", 1)
        tools.runType = AgentRunType.NIGHTLY
        assertFalse(tools.execute("ask_wondering", ask.toString()).ok)
        assertTrue(delivered.isEmpty())
        tools.runType = AgentRunType.CHAT
        call("ask_wondering", ask)
        assertEquals(1, delivered.size)
        assertEquals("ASKED", repo.getWondering(id)!!.status)
        assertFalse(tools.execute("ask_wondering", ask.put("expectedVersion", 2).toString()).ok)
        val second = wonder(task()).getString("id")
        assertFalse(tools.execute("ask_wondering", JSONObject().put("id", second).put("expectedVersion", 1).toString()).ok)
        assertEquals(1, delivered.size)
    }

    @Test fun sourceIdAliasStillEnforcesVersionAndCooldown() = runBlocking {
        val source = task()
        wonder(source)
        val ask = JSONObject().put("id", source).put("expectedVersion", 9)
        assertFalse(tools.execute("ask_wondering", ask.toString()).ok)
        call("ask_wondering", ask.put("expectedVersion", 1))
        val second = task()
        wonder(second)
        val next = JSONObject().put("id", second).put("expectedVersion", 1)
        assertFalse(tools.execute("ask_wondering", next.toString()).ok)
        clock.advance(4 * 86_400_000L)
        call("ask_wondering", next)
        assertEquals(2, delivered.size)
        assertEquals("ASKED", repo.getWondering("wondering:$second")!!.status)
    }

    @Test fun emptyPeopleSearchIsAValidReview() = runBlocking {
        assertEquals(0, call("search_people", JSONObject().put("query", "")).getJSONArray("people").length())
        assertEquals(0, call("search_people", JSONObject()).getJSONArray("people").length())
    }

    @Test fun resolutionRequiresRealQuotedAnswerAndPreservesTask() = runBlocking {
        val source = task()
        val id = wonder(source).getString("id")
        val args = JSONObject().put("sourceId", source).put("status", "RESOLVED").put("expectedVersion", 1)
            .put("sourceMessageId", "invented").put("evidence", "Waiting for feedback")
        assertFalse(tools.execute("save_wondering", args.toString()).ok)
        val message = repo.newChatMessage(MessageRole.USER, "Waiting for feedback", MessageStatus.SENT)
        repo.saveMessage(message)
        call("save_wondering", args.put("sourceMessageId", message.id))
        assertEquals("RESOLVED", repo.getWondering(id)!!.status)
        assertEquals(TaskStatus.OPEN, repo.getTask(source)!!.status)
        assertFalse(tools.execute("save_wondering", JSONObject().put("sourceId", source).put("status", "OPEN").put("expectedVersion", 2).toString()).ok)
        repo.forgetKnowledge(message.id)
        assertNotNull(repo.getWondering(id)!!.deletedAt)
        assertEquals("{}", repo.getWondering(id)!!.content)
    }

    @Test fun reviewUsesAgeAndClosesCompletedSources() = runBlocking {
        val source = task()
        val id = wonder(source).getString("id")
        val task = repo.getTask(source)!!
        repo.saveTask(task.copy(dueAt = clock.peek() - 2 * 86_400_000L))
        val purchase = call("add_grocery", JSONObject().put("name", "Desk")).getString("id")
        val grocery = repo.getGrocery(purchase)!!
        repo.saveGrocery(grocery.copy(createdAt = clock.peek() - 8 * 86_400_000L))
        val review = call("review_wonderings", JSONObject())
        assertEquals(2, review.getJSONArray("candidates").length())
        repo.saveTask(task.copy(status = TaskStatus.DONE))
        call("review_wonderings", JSONObject())
        assertEquals("OBSOLETE", repo.getWondering(id)!!.status)
    }

    @Test fun forgettingSurvivesStaleSyncAndDuplicateWritesNeedVersion() = runBlocking {
        val source = task()
        val id = wonder(source).getString("id")
        assertFalse(tools.execute("save_wondering", JSONObject().put("sourceId", source).put("status", "OPEN").toString()).ok)
        val snapshot = repo.getWondering(id)!!
        assertTrue(SyncCodec.persist(repo, "wondering", JSONObject(snapshot.toJson())))
        repo.forgetKnowledge(source)
        SyncCodec.persist(repo, "wondering", JSONObject(snapshot.copy(version = 50, updatedAt = clock.peek() + 100).toJson()))
        assertNotNull(repo.getWondering(id)!!.deletedAt)
        assertEquals("{}", repo.getWondering(id)!!.content)
    }

    @Test fun migrationKeepsExistingMemory() = runBlocking {
        val name = "wondering_migration_${System.nanoTime()}"
        var disk = Room.databaseBuilder(context, HerDatabase::class.java, name).allowMainThreadQueries().build()
        disk.openHelper.writableDatabase.execSQL("INSERT INTO companion_records VALUES ('keep','keep','THREAD','{}',1,1,'test',1,NULL)")
        disk.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE wondering"); it.version = 7
        }
        disk = Room.databaseBuilder(context, HerDatabase::class.java, name).addMigrations(HerDatabase.MIGRATION_7_8).allowMainThreadQueries().build()
        assertNotNull(disk.companionDao().get("keep"))
        assertTrue(disk.wonderingDao().all().isEmpty())
        disk.close(); context.deleteDatabase(name); Unit
    }
}
