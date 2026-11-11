package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.data.db.ChatMessageEntity
import com.her.data.db.GroceryEntity
import com.her.data.db.HerDatabase
import com.her.data.db.UserUnderstandingEntity
import com.her.domain.GroceryStatus
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
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

    @Test
    fun userUnderstandingRoundTrip() = runBlocking {
        db.userUnderstandingDao().upsert(
            UserUnderstandingEntity(
                id = "u1",
                facet = "communication",
                content = "Prefers short answers",
                confidence = 0.8,
                importance = 0.7,
                source = MemorySource.USER_EXPLICIT,
                sourceMessageId = null,
                status = MemoryStatus.ACTIVE,
                createdAt = 1,
                updatedAt = 1,
                deviceId = "dev",
                version = 1,
                deletedAt = null,
            ),
        )
        val active = db.userUnderstandingDao().byStatus(MemoryStatus.ACTIVE)
        assertEquals(1, active.size)
        assertEquals("communication", active.single().facet)
        assertEquals("Prefers short answers", db.userUnderstandingDao().byFacet("communication", MemoryStatus.ACTIVE)?.content)
    }

    @Test
    fun migrationFromVersionElevenPutsExistingItemsOnTheGroceriesList() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "lists_migration_${System.nanoTime()}.db"
        Room.databaseBuilder(context, HerDatabase::class.java, name).build().apply { openHelper.writableDatabase; close() }
        // Roll the file back to version 11, when the table had no list column.
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE groceries")
            it.execSQL("""CREATE TABLE `groceries` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity` TEXT, `category` TEXT,
                `status` TEXT NOT NULL, `reason` TEXT, `recurrenceScore` REAL NOT NULL, `notes` TEXT, `store` TEXT,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `version` INTEGER NOT NULL,
                `deletedAt` INTEGER, PRIMARY KEY(`id`))""")
            it.execSQL("INSERT INTO groceries VALUES ('g1','eggs',NULL,NULL,'ACTIVE',NULL,0.0,NULL,NULL,1,1,'dev',1,NULL)")
            it.version = 11
        }
        val migrated = Room.databaseBuilder(context, HerDatabase::class.java, name).addMigrations(HerDatabase.MIGRATION_11_12).build()
        assertEquals("groceries", migrated.groceryDao().get("g1")?.list)
        migrated.groceryDao().upsert(migrated.groceryDao().get("g1")!!.copy(id = "g2", name = "passport", list = "packing"))
        assertEquals(listOf("groceries", "packing"), migrated.groceryDao().allActive().map { it.list })
        migrated.close()
        context.deleteDatabase(name)
        Unit
    }
}
