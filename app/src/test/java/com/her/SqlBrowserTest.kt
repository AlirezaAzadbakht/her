package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.data.db.HerDatabase
import com.her.data.db.ImportantDateEntity
import com.her.data.db.LongTermMemoryEntity
import com.her.data.db.MemoryCounts
import com.her.data.db.PersonEntity
import com.her.data.db.SqlBrowser
import com.her.data.db.UserUnderstandingEntity
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SqlBrowserTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HerDatabase::class.java)
        .allowMainThreadQueries().build()
    private val browser = SqlBrowser(db)

    @After
    fun tearDown() = db.close()

    @Test
    fun recentMemoriesAreNewestFirstAndSkipForgottenOrStale() = runBlocking {
        db.memoryDao().upsertLong(longMemory("l1", "Sara's birthday is 3 March", at = 10))
        db.memoryDao().upsertLong(longMemory("l2", "Lives in Tehran", at = 30).copy(status = MemoryStatus.HISTORICAL))
        db.memoryDao().upsertLong(longMemory("l3", "Forgotten", at = 40).copy(deletedAt = 41))
        db.userUnderstandingDao().upsert(
            UserUnderstandingEntity("u1", "rhythm", "Prefers mornings for deep work", 0.8, 0.5, MemorySource.AGENT_INFERENCE, null, MemoryStatus.ACTIVE, 5, 20, "dev", 1, null),
        )
        val recent = browser.recentMemories()
        assertEquals(listOf("learnt", "remembered"), recent.map { it.kind })
        assertEquals("Prefers mornings for deep work", recent.first().content)
        assertEquals(5L, recent.first().createdAt)
    }

    @Test
    fun recentMemoriesIncludeImportantDates() = runBlocking {
        db.memoryDao().upsertLong(longMemory("l1", "Lives in Tehran", at = 10))
        db.importantDateDao().upsert(ImportantDateEntity("d1", "Ali's birthday", "1990-10-04", "yearly", null, null, 0.7, 20, 20, "dev", 1, null))
        db.importantDateDao().upsert(ImportantDateEntity("d2", "Gone", "2020-01-01", null, null, null, 0.7, 30, 30, "dev", 1, 31))
        val recent = browser.recentMemories()
        assertEquals(listOf("dated", "remembered"), recent.map { it.kind })
        assertEquals("Ali's birthday · 1990-10-04", recent.first().content)
    }

    @Test
    fun tablesSkipFullTextShadowTables() {
        val names = browser.tables().map { it.name }
        assertTrue("chat_messages_fts" in names)
        assertTrue(names.none { it.startsWith("chat_messages_fts_") })
    }

    @Test
    fun offsetPagesAnyReadOnlyQuery() {
        val sql = "WITH n(x) AS (VALUES (1), (2), (3)) SELECT x FROM n"
        val middle = browser.query(sql, rowCap = 1, offset = 1)
        assertEquals(listOf(listOf<String?>("2")), middle.rows)
        assertTrue(middle.truncated)
        val last = browser.query(sql, rowCap = 1, offset = 2)
        assertEquals(listOf(listOf<String?>("3")), last.rows)
        assertFalse(last.truncated)
        assertTrue(browser.query(sql, rowCap = 1, offset = 9).rows.isEmpty())
    }

    @Test
    fun countsTopicsAndPeopleSkipForgottenOrStale() = runBlocking {
        db.memoryDao().upsertLong(longMemory("l1", "Sara's birthday is 3 March", at = 10).copy(category = "family"))
        db.memoryDao().upsertLong(longMemory("l2", "Has a sister", at = 20).copy(category = "family", importance = 0.9))
        db.memoryDao().upsertLong(longMemory("l3", "Likes tea", at = 30).copy(category = "taste"))
        db.memoryDao().upsertLong(longMemory("l4", "Old", at = 40).copy(category = "taste", status = MemoryStatus.HISTORICAL))
        db.userUnderstandingDao().upsert(
            UserUnderstandingEntity("u1", "rhythm", "Prefers mornings", 0.8, 0.5, MemorySource.AGENT_INFERENCE, null, MemoryStatus.ACTIVE, 5, 20, "dev", 1, null),
        )
        db.personDao().upsert(PersonEntity("p1", "Sara", "sister", null, "Plays violin", null, 10, 0.8, 1, 1, "dev", 1, null))
        db.personDao().upsert(PersonEntity("p2", "Reza", "friend", null, null, null, 50, 0.8, 1, 1, "dev", 1, null))
        db.personDao().upsert(PersonEntity("p3", "Gone", null, null, null, null, 90, 0.8, 1, 1, "dev", 1, 91))

        assertEquals(MemoryCounts(remembered = 3, learnt = 1, people = 2), browser.memoryCounts())

        val topics = browser.knownTopics()
        assertEquals(listOf("family", "taste", "rhythm"), topics.map { it.name })
        assertEquals(listOf("Has a sister", "Sara's birthday is 3 March"), topics.first().items)
        assertEquals("learnt", topics.last().kind)

        val people = browser.people()
        assertEquals(listOf("Reza", "Sara"), people.map { it.name })
        assertTrue("Plays violin" in people.last().details)
    }

    @Test
    fun recordQueriesReachOnlyRecordTables() {
        val ok = browser.queryRecords("SELECT COUNT(*) AS n FROM tasks WHERE deletedAt IS NULL", rowCap = 10)
        assertEquals(null, ok.error)
        assertEquals("0", ok.rows.single().single())
        assertTrue(browser.queryRecords("WITH t AS (SELECT title FROM goals) SELECT * FROM t", 10).error == null)
        assertTrue("tasks" in browser.recordColumns().keys)

        listOf(
            "SELECT * FROM long_term_memories",
            "SELECT t.title FROM tasks t JOIN chat_messages c ON c.id = t.id",
            "SELECT * FROM tasks WHERE id IN (SELECT id FROM user_understandings)",
            "SELECT * FROM \"long_term_memories\"",
            "SELECT * FROM 'chat_messages'",
            "SELECT * FROM chat_messages_fts_content",
            "SELECT name FROM sqlite_master",
            "PRAGMA table_info(tasks)",
            "DELETE FROM tasks",
            "SELECT 1; DELETE FROM tasks",
        ).forEach { assertTrue(it, browser.queryRecords(it, 10).error != null) }
    }

    private fun longMemory(id: String, content: String, at: Long) = LongTermMemoryEntity(
        id = id,
        content = content,
        category = "general",
        confidence = 0.8,
        importance = 0.5,
        createdAt = at,
        updatedAt = at,
        lastConfirmedAt = null,
        source = MemorySource.USER_EXPLICIT,
        sourceMessageId = null,
        derivedFromJson = null,
        validFrom = at,
        validUntil = null,
        status = MemoryStatus.ACTIVE,
        metadataJson = null,
        deviceId = "dev",
        version = 1,
        deletedAt = null,
    )
}
