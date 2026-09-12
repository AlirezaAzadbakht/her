package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.data.db.ChatMessageEntity
import com.her.data.db.GroceryEntity
import com.her.data.db.HerDatabase
import com.her.domain.GroceryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
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
class RoomDaoTest {
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
    fun chatRoundTripAndSearch() = runBlocking {
        db.chatDao().upsert(
            ChatMessageEntity(
                id = "m1",
                role = MessageRole.USER,
                content = "We're out of coffee",
                createdAt = 1,
                updatedAt = 1,
                deviceId = "dev",
                version = 1,
                deletedAt = null,
                status = MessageStatus.SENT,
                metadataJson = null,
            ),
        )
        val recent = db.chatDao().recent(5)
        assertEquals(1, recent.size)
        assertEquals("We're out of coffee", recent.first().content)
    }

    @Test
    fun pendingAndLatestAssistant() = runBlocking {
        db.chatDao().upsert(
            ChatMessageEntity(
                id = "u1",
                role = MessageRole.USER,
                content = "one",
                createdAt = 1,
                updatedAt = 1,
                deviceId = "dev",
                version = 1,
                deletedAt = null,
                status = MessageStatus.PENDING,
                metadataJson = null,
            ),
        )
        db.chatDao().upsert(
            ChatMessageEntity(
                id = "a1",
                role = MessageRole.ASSISTANT,
                content = "hello",
                createdAt = 2,
                updatedAt = 2,
                deviceId = "dev",
                version = 1,
                deletedAt = null,
                status = MessageStatus.SENT,
                metadataJson = null,
            ),
        )
        assertEquals(1, db.chatDao().pending().size)
        db.chatDao().markStatus(listOf("u1"), MessageStatus.SENT, 3)
        assertEquals(0, db.chatDao().pending().size)
        assertEquals(MessageStatus.SENT, db.chatDao().get("u1")?.status)
    }

    @Test
    fun groceryStatusPersists() = runBlocking {
        db.groceryDao().upsert(
            GroceryEntity(
                id = "g1",
                name = "eggs",
                quantity = "1 dozen",
                category = "dairy",
                status = GroceryStatus.ACTIVE,
                reason = "out",
                recurrenceScore = 0.2,
                notes = null,
                store = null,
                createdAt = 1,
                updatedAt = 1,
                deviceId = "dev",
                version = 1,
                deletedAt = null,
            ),
        )
        assertEquals(GroceryStatus.ACTIVE, db.groceryDao().get("g1")?.status)
    }
}
