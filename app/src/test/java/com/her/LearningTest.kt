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
import com.her.domain.ChatMessage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.learning.LearningProgress
import com.her.learning.learningContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LearningTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "learning_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-10-16T12:00:00Z").toEpochMilli())
    private val repo = HerRepository(db, settings, clock)
    private val notifications = mutableListOf<String>()
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), onUserMessage = { notifications.add(it) })
    @After fun close() = db.close()
    private suspend fun call(name: String, args: String): JSONObject {
        val result = tools.execute(name, args)
        assertTrue(result.payloadJson, result.ok)
        return JSONObject(result.payloadJson)
    }
    private suspend fun seed() {
        call("save_learning_path", """{"id":"python","title":"Python","goal":"Automate tasks"}""")
        call("save_learning_lesson", """{"id":"variables","pathId":"path:python","title":"Variables","outcome":"Assign a value"}""")
        call("set_learning_exercise", """{"id":"first","lessonId":"lesson:variables","prompt":"What is 2 + 2?","mode":"EXACT","acceptedAnswers":["4","four"]}""")
    }
    private suspend fun message(id: String, text: String) {
        val now = clock.nowMillis()
        repo.saveMessage(ChatMessage(id, MessageRole.USER, text, now, now, repo.deviceId, 1, null, MessageStatus.SENT, null))
    }
    private suspend fun answer(id: String, text: String) = call("record_learning_answer",
        """{"exerciseId":"exercise:first","sourceMessageId":"$id","answer":"$text","result":"CORRECT","feedback":"Practice feedback"}""")

    @Test fun exactGradingDeduplicatesAndSchedulesReview() = runBlocking {
        seed(); message("a1", "5")
        assertEquals("INCORRECT", answer("a1", "5").getString("result"))
        assertTrue(answer("a1", "5").getBoolean("duplicate"))
        assertEquals(1, repo.learningRecords().count { it.kind == "ATTEMPT" })
        message("a2", "FOUR")
        assertEquals("CORRECT", answer("a2", "FOUR").getString("result"))
        val attempts = repo.learningRecords().filter { it.kind == "ATTEMPT" }
        val review = LearningProgress.review(attempts)
        assertEquals(1, review.successes)
        assertEquals(attempts.last().createdAt + LearningProgress.DAY, review.dueAt)
        assertEquals("PLANNED", repo.getLearningRecord("lesson:variables")!!.data().getString("status"))
        assertTrue(repo.pendingSyncOps().any { it.entityType == "learning_records" })
    }
    @Test fun unrelatedChatAndRoadmapEditsKeepExerciseAndAttempts() = runBlocking {
        seed(); message("a1", "4"); answer("a1", "4"); message("other", "What is for dinner?")
        call("save_learning_lesson", """{"id":"variables","pathId":"path:python","expectedVersion":1,"title":"Values and variables","order":2}""")
        val context = learningContext(repo).joinToString()
        assertTrue(context.contains("What is 2 + 2?"))
        assertTrue(context.contains("last result=CORRECT"))
        assertTrue(context.contains("Values and variables"))
        assertEquals(1, repo.learningRecords().count { it.kind == "ATTEMPT" })
        assertFalse(tools.execute("save_learning_lesson", """{"id":"variables","pathId":"path:python","expectedVersion":1,"title":"Stale edit"}""").ok)
    }
    @Test fun pauseBlocksPracticeAndNudgesAndCanResume() = runBlocking {
        seed()
        call("save_learning_path", """{"id":"python","expectedVersion":1,"status":"PAUSED","remindersEnabled":true,"reminderTime":"09:00"}""")
        message("a1", "4")
        assertFalse(tools.execute("record_learning_answer", """{"exerciseId":"exercise:first","sourceMessageId":"a1","answer":"4","feedback":"yes"}""").ok)
        assertFalse(call("send_learning_reminder", """{"pathId":"path:python","content":"Ready?"}""").getBoolean("queued"))
        call("save_learning_path", """{"id":"python","expectedVersion":2,"status":"ACTIVE"}""")
        assertEquals("CORRECT", answer("a1", "4").getString("result"))
    }
    @Test fun nudgesRequireOptInAndRespectQuietHoursAndDailyDedup() = runBlocking {
        seed()
        call("update_user_profile", """{"timezone":"UTC"}""")
        val args = """{"pathId":"path:python","content":"Ready to practice?"}"""
        assertFalse(call("send_learning_reminder", args).getBoolean("queued"))
        call("save_learning_path", """{"id":"python","expectedVersion":1,"remindersEnabled":true,"reminderTime":"09:00"}""")
        assertTrue(call("send_learning_reminder", args).getBoolean("queued"))
        assertFalse(call("send_learning_reminder", args).getBoolean("queued"))
        clock.set(Instant.parse("2026-10-17T23:45:00Z").toEpochMilli())
        assertFalse(call("send_learning_reminder", args).getBoolean("queued"))
        clock.set(Instant.parse("2026-10-18T08:30:00Z").toEpochMilli())
        assertFalse(call("send_learning_reminder", args).getBoolean("queued"))
        clock.set(Instant.parse("2026-10-18T10:00:00Z").toEpochMilli())
        assertTrue(call("send_learning_reminder", args).getBoolean("queued"))
        assertEquals(2, repo.learningRecords().count { it.kind == "NUDGE" })
    }
    @Test fun rejectsFabricatedAnswersAndImmutableExerciseEdits() = runBlocking {
        seed(); message("a1", "I need a hint")
        assertFalse(tools.execute("record_learning_answer", """{"exerciseId":"exercise:first","sourceMessageId":"a1","answer":"4","feedback":"yes"}""").ok)
        assertFalse(tools.execute("set_learning_exercise", """{"id":"first","lessonId":"lesson:variables","prompt":"Changed?","mode":"EXACT","acceptedAnswers":["1"]}""").ok)
        assertFalse(tools.execute("save_learning_path", """{"id":"bad","title":"X","goal":"Y","status":"BOGUS"}""").ok)
        assertNull(repo.getLearningRecord("path:bad"))
    }
    @Test fun learningReadsPageWithinTheToolBudgetAndRetrieveSpecificProgress() = runBlocking {
        seed(); message("a1", "4"); answer("a1", "4")
        repeat(15) { index ->
            call("save_learning_lesson", JSONObject().put("id", "extra-$index").put("pathId", "path:python")
                .put("title", "Lesson $index").put("outcome", "Understand $index").put("content", "x".repeat(2500)).toString())
        }
        var offset = 0
        val ids = mutableSetOf<String>()
        do {
            val page = call("get_learning", """{"pathId":"path:python","offset":$offset}""")
            assertTrue(page.toString().length < 8000)
            val records = page.getJSONArray("records")
            for (i in 0 until records.length()) assertTrue(ids.add(records.getJSONObject(i).getString("id")))
            offset = if (page.isNull("nextOffset")) -1 else page.getInt("nextOffset")
        } while (offset >= 0)
        assertEquals(repo.learningPathRecords("path:python").size, ids.size)
        val lesson = call("get_learning", """{"recordId":"lesson:variables"}""")
        assertEquals(1, lesson.getJSONArray("progress").getJSONObject(0).getInt("attempts"))
    }

    @Test fun openAnswerUncertaintyDoesNotAwardMastery() = runBlocking {
        seed()
        call("set_learning_exercise", """{"id":"reasoning","lessonId":"lesson:variables","prompt":"Why use a variable?","mode":"OPEN","rubric":"Names a reusable value"}""")
        message("a1", "Maybe storage")
        call("record_learning_answer", """{"exerciseId":"exercise:reasoning","sourceMessageId":"a1","answer":"Maybe storage","result":"UNCERTAIN","feedback":"Can you give an example?"}""")
        val review = LearningProgress.review(repo.learningRecords().filter { it.kind == "ATTEMPT" })
        assertEquals("UNASSESSED", review.mastery)
        assertNull(review.dueAt)
    }
    @Test fun upgradeFromFivePreservesExistingDataAndLearningSurvivesReopen() = runBlocking {
        val name = "learning_migration_${System.nanoTime()}.db"
        var disk = Room.databaseBuilder(context, HerDatabase::class.java, name).allowMainThreadQueries().build()
        disk.openHelper.writableDatabase.execSQL("INSERT INTO learning_records VALUES ('old', 'old', 'PATH', '{}', 1, 1, 'test', 1, NULL)")
        disk.openHelper.writableDatabase.execSQL("CREATE TABLE migration_sentinel (value TEXT)")
        disk.openHelper.writableDatabase.execSQL("INSERT INTO migration_sentinel VALUES ('keep')")
        disk.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE learning_records")
            it.version = 5
        }
        disk = Room.databaseBuilder(context, HerDatabase::class.java, name).addMigrations(HerDatabase.MIGRATION_5_6, HerDatabase.MIGRATION_6_7, HerDatabase.MIGRATION_7_8, HerDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        disk.openHelper.readableDatabase.query("SELECT value FROM migration_sentinel").use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
        val diskRepo = HerRepository(disk, settings, clock)
        seed()
        repo.learningRecords().forEach { diskRepo.saveLearningRecord(it) }
        disk.close()
        disk = Room.databaseBuilder(context, HerDatabase::class.java, name).allowMainThreadQueries().build()
        assertEquals("What is 2 + 2?", disk.learningDao().get("exercise:first")!!.data().getString("prompt"))
        disk.close(); context.deleteDatabase(name); Unit
    }
}
