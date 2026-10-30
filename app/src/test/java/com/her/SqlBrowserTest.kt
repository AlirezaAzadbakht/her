package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.data.db.HerDatabase
import com.her.data.db.LongTermMemoryEntity
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
