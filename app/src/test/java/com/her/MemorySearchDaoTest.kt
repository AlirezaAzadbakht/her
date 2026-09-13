package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.ftsQuery
import com.her.data.db.HerDatabase
import com.her.data.db.LongTermMemoryEntity
import com.her.data.db.ShortTermMemoryEntity
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MemorySearchDaoTest {
    private lateinit var db: HerDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aFullSentenceFindsAMemoryThatSharesOnlySomeWords() = runBlocking {
        db.memoryDao().upsertLong(longMemory("l1", "The living room needs a 15-watt lamp"))
        db.memoryDao().upsertLong(longMemory("l2", "Sara prefers tea over coffee"))
        val hits = db.memoryDao().searchLong(ftsQuery("what lamp did I say the hallway needs?")!!, 10)
        assertEquals(listOf("l1"), hits.map { it.id })
    }

    @Test
    fun persianQueryFindsPersianMemory() = runBlocking {
        db.memoryDao().upsertLong(longMemory("l1", "برنج ایرانی را از فروشگاه محله می‌خریم"))
        db.memoryDao().upsertLong(longMemory("l2", "Sara prefers tea over coffee"))
        val hits = db.memoryDao().searchLong(ftsQuery("برنج کجا بخریم")!!, 10)
        assertEquals(listOf("l1"), hits.map { it.id })
    }

    @Test
    fun expiredShortTermMemoryIsNotSearchable() = runBlocking {
        db.memoryDao().upsertShort(shortMemory("s1", "Feeling tired after the trip", expiresAt = 100))
        db.memoryDao().upsertShort(shortMemory("s2", "Trip photos still need sorting", expiresAt = null))
        val hits = db.memoryDao().searchShort(ftsQuery("trip")!!, now = 200, limit = 10)
        assertEquals(listOf("s2"), hits.map { it.id })
    }

    private fun longMemory(id: String, content: String) = LongTermMemoryEntity(
        id = id,
        content = content,
        category = "general",
        confidence = 0.8,
        importance = 0.5,
        createdAt = 1,
        updatedAt = 1,
        lastConfirmedAt = null,
        source = MemorySource.USER_EXPLICIT,
        sourceMessageId = null,
        derivedFromJson = null,
        validFrom = 1,
        validUntil = null,
        status = MemoryStatus.ACTIVE,
        metadataJson = null,
        deviceId = "dev",
        version = 1,
        deletedAt = null,
    )

    private fun shortMemory(id: String, content: String, expiresAt: Long?) = ShortTermMemoryEntity(
        id = id,
        content = content,
        type = "context",
        confidence = 0.6,
        importance = 0.4,
        createdAt = 1,
        updatedAt = 1,
        expiresAt = expiresAt,
        sourceMessageId = null,
        source = MemorySource.AGENT_INFERENCE,
        metadataJson = null,
        deviceId = "dev",
        version = 1,
        deletedAt = null,
    )
}
