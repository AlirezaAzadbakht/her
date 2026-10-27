package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.data.db.HerDatabase
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.retrieval.strengthenRecalled
import com.her.data.secure.AppSettingsStore
import com.her.domain.LongTermMemory
import com.her.domain.MemoryRelationship
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MemoryAssociationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var ranker: HybridRanker

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        repo = HerRepository(db, AppSettingsStore(context, "association_test_${System.nanoTime()}"), FakeClock(Instant.parse("2026-10-27T10:00:00Z").toEpochMilli()))
        ranker = HybridRanker(repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun shortFollowUpKeepsThePreviousTopic() = runBlocking {
        repo.upsertLong(memory("dentist", "Dentist appointment is at the Saint Mary clinic"))
        repo.upsertLong(memory("jazz", "Loves late night jazz records", importance = 0.9))
        val followUp = "ok and what about tomorrow?"

        assertEquals("jazz", ranker.search(followUp, limit = 2).first().id)
        assertEquals("dentist", ranker.search(followUp, limit = 2, context = "When is my dentist appointment?").first().id)
    }

    @Test
    fun unrelatedRelationshipNoLongerBoosts() = runBlocking {
        repo.upsertLong(memory("tea", "Drinks green tea"))
        repo.upsertLong(memory("coffee", "Drinks black coffee"))
        repo.saveRelationship(relation("coffee", "person-unrelated"))

        val hits = ranker.search("tea or coffee", limit = 2).associate { it.id to it.score }
        assertEquals(hits.getValue("tea"), hits.getValue("coffee"), 1e-9)
    }

    @Test
    fun memoriesLearnedTogetherComeBackTogether() = runBlocking {
        repo.upsertLong(memory("berlin", "Moved to Berlin in March", sourceMessageId = "msg-1"))
        repo.upsertLong(memory("nights", "Works night shifts at the hospital", sourceMessageId = "msg-1"))
        repo.upsertLong(memory("plants", "Keeps a balcony full of tomato plants", sourceMessageId = "msg-2"))

        val ids = ranker.search("How is Berlin treating you", limit = 5).map { it.id }
        assertTrue("nights" in ids)
        assertFalse("plants" in ids)

        repo.forgetKnowledge("nights")
        assertFalse("nights" in ranker.search("How is Berlin treating you", limit = 5).map { it.id })
    }

    @Test
    fun aNamedPersonBringsUpTheirLinkedMemories() = runBlocking {
        repo.upsertLong(memory("dentist", "Dentist tomorrow morning"))
        repo.upsertLong(memory("chess", "Bought a chess set for his birthday"))
        repo.saveRelationship(relation("chess", "person-reza"))
        val query = "Seeing Reza tomorrow"

        assertFalse("chess" in ranker.search(query, limit = 5).map { it.id })
        assertTrue("chess" in ranker.search(query, limit = 5, relatedIds = setOf("person-reza")).map { it.id })
    }

    @Test
    fun aMemoryBroughtUpAgainOutranksAnEqualForgottenOne() = runBlocking {
        val monthAgo = repo.clock.nowMillis() - 30L * DAY
        repo.upsertLong(memory("pottery", "Takes a pottery class on Tuesdays").copy(updatedAt = monthAgo))
        repo.upsertLong(memory("climbing", "Takes a climbing class on Tuesdays").copy(updatedAt = monthAgo))
        repo.confirmLong("climbing", repo.clock.nowMillis())

        assertEquals("climbing", ranker.search("class on Tuesdays", limit = 2).first().id)
    }

    @Test
    fun onlyTheirOwnMentionStrengthensAndAtMostDaily() = runBlocking {
        repo.upsertLong(memory("pottery", "Takes a pottery class on Tuesdays"))
        repo.upsertLong(memory("violin", "Plays violin in a small orchestra"))
        val clock = repo.clock as FakeClock
        suspend fun mention(text: String) = strengthenRecalled(repo, ranker.search(text, limit = 2), text)

        mention("My pottery class on Tuesdays got cancelled")
        val first = repo.getLong("pottery")!!.lastConfirmedAt!!
        assertEquals(null, repo.getLong("violin")!!.lastConfirmedAt)

        clock.advance(3_600_000)
        mention("Pottery class on Tuesdays again")
        assertEquals(first, repo.getLong("pottery")!!.lastConfirmedAt)

        clock.advance(DAY)
        mention("Pottery class on Tuesdays again")
        assertTrue(repo.getLong("pottery")!!.lastConfirmedAt!! > first + DAY)
    }

    private fun relation(memoryId: String, personId: String): MemoryRelationship {
        val now = repo.clock.nowMillis()
        return MemoryRelationship("rel-$memoryId", "memory", memoryId, "about", "person", personId, now, now, repo.deviceId, 1, null)
    }

    private fun memory(id: String, content: String, importance: Double = 0.5, sourceMessageId: String? = null): LongTermMemory {
        val now = repo.clock.nowMillis()
        return LongTermMemory(id, content, "general", 0.8, importance, now, now, null, MemorySource.USER_EXPLICIT, sourceMessageId,
            null, now, null, MemoryStatus.ACTIVE, null, repo.deviceId, 1, null)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
