package com.her

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.data.db.HerDatabase
import com.her.data.repository.HerRepository
import com.her.data.retrieval.EmbeddingProvider
import com.her.data.retrieval.HybridRanker
import com.her.data.retrieval.OpenAiEmbeddingProvider
import com.her.data.retrieval.blobToFloats
import com.her.data.retrieval.cosineSimilarity
import com.her.data.retrieval.parseEmbeddings
import com.her.data.retrieval.toBlob
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.LongTermMemory
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EmbeddingRetrievalTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        repo = HerRepository(
            db,
            AppSettingsStore(context, "embedding_test_${System.nanoTime()}"),
            FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun meaningFindsAMemoryThatSharesNoWords() = runBlocking {
        repo.upsertLong(memory("car", "The family car is a red Toyota", importance = 0.3))
        repo.upsertLong(memory("rice", "We ran out of rice", importance = 0.9))
        val query = "which vehicle do we drive"

        assertEquals("rice", HybridRanker(repo).search(query, limit = 2).first().id)

        val fake = FakeEmbeddings()
        assertEquals("car", HybridRanker(repo, fake).search(query, limit = 2).first().id)
        assertEquals(2, db.embeddingDao().count())

        fake.inputs.clear()
        HybridRanker(repo, fake).search(query, limit = 2)
        assertEquals("stored vectors are reused; only the query is embedded", listOf(query), fake.inputs)
    }

    @Test
    fun changedContentIsEmbeddedAgain() = runBlocking {
        repo.upsertLong(memory("m1", "The family car is a red Toyota"))
        val fake = FakeEmbeddings()
        HybridRanker(repo, fake).search("vehicle", limit = 1)
        repo.upsertLong(memory("m1", "The family car is now a blue Honda"))
        fake.inputs.clear()
        HybridRanker(repo, fake).search("vehicle", limit = 1)
        assertEquals(listOf("vehicle", "The family car is now a blue Honda"), fake.inputs)
    }

    @Test
    fun providerFailureFallsBackToLexicalRanking() = runBlocking {
        repo.upsertLong(memory("m1", "We ran out of rice"))
        val failing = object : EmbeddingProvider {
            override fun model() = "broken"
            override suspend fun embed(texts: List<String>): List<FloatArray> = throw IOException("offline")
        }
        assertEquals("m1", HybridRanker(repo, failing).search("rice", limit = 1).single().id)
    }

    @Test
    fun embeddingsResponseIsReadInInputOrder() {
        val vectors = parseEmbeddings(
            """{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.5]}]}""",
        )
        assertArrayEquals(floatArrayOf(1f, 0.5f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), vectors[1], 0f)
    }

    @Test
    fun vectorMathAndStorage() {
        assertEquals(1.0, cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f))!!, 1e-6)
        assertEquals(0.0, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, 1e-6)
        assertNull(cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)))
        assertNull(cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)))
        val vector = floatArrayOf(0.25f, -1.5f, 3f)
        assertArrayEquals(vector, vector.toBlob().blobToFloats(), 0f)
    }

    @Test
    fun providerIsOffUntilEnabledAndConfigured() {
        val configured = LlmSettings("http://test", "key", "chat-model")
        assertNull(OpenAiEmbeddingProvider({ configured }, { null }).model())
        assertNull(OpenAiEmbeddingProvider({ LlmSettings("", "", "") }, { "text-embedding-3-small" }).model())
        assertEquals("text-embedding-3-small", OpenAiEmbeddingProvider({ configured }, { " text-embedding-3-small " }).model())
    }

    @Test
    fun migrationFromVersionTwoAddsTheEmbeddingTable() = runBlocking {
        val name = "migration_${System.nanoTime()}.db"
        Room.databaseBuilder(context, HerDatabase::class.java, name).build().apply {
            openHelper.writableDatabase
            close()
        }
        // Roll the file back to version 2, before the embedding cache existed.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE memory_embeddings")
            it.version = 2
        }
        val migrated = Room.databaseBuilder(context, HerDatabase::class.java, name)
            .addMigrations(HerDatabase.MIGRATION_1_2, HerDatabase.MIGRATION_2_3)
            .build()
        assertEquals(0, migrated.embeddingDao().count())
        migrated.close()
        context.deleteDatabase(name)
        Unit
    }

    private fun memory(id: String, content: String, importance: Double = 0.5): LongTermMemory {
        val now = repo.clock.nowMillis()
        return LongTermMemory(
            id = id,
            content = content,
            category = "general",
            confidence = 0.8,
            importance = importance,
            createdAt = now,
            updatedAt = now,
            lastConfirmedAt = null,
            source = MemorySource.USER_EXPLICIT,
            sourceMessageId = null,
            derivedFromJson = null,
            validFrom = now,
            validUntil = null,
            status = MemoryStatus.ACTIVE,
            metadataJson = null,
            deviceId = "dev",
            version = 1,
            deletedAt = null,
        )
    }

    /** Two-axis toy space: vehicles on one axis, food on the other. */
    private class FakeEmbeddings : EmbeddingProvider {
        val inputs = mutableListOf<String>()

        override fun model() = "fake"

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            inputs += texts
            return texts.map { text ->
                val lower = text.lowercase()
                val vehicle = listOf("car", "toyota", "honda", "vehicle", "drive").any { it in lower }
                val food = listOf("rice", "food").any { it in lower }
                floatArrayOf(if (vehicle) 1f else 0f, if (food) 1f else 0f, 0.1f)
            }
        }
    }
}
