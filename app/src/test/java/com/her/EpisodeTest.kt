package com.her

import android.app.Application
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
import com.her.domain.AgentRunType
import com.her.domain.LongTermMemory
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
class EpisodeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "episode_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-10-27T10:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), { }).apply { runType = AgentRunType.NIGHTLY }

    @After fun close() = db.close()

    private suspend fun episode(period: String, start: String, content: String = "Talked about the move to Berlin") =
        tools.execute("save_episode", JSONObject().put("period", period).put("start", start).put("content", content).toString())

    /** A user message at 10:00 UTC on [day], plus a fact learned from it. */
    private suspend fun talk(day: String, fact: String): String {
        clock.set(Instant.parse("${day}T10:00:00Z").toEpochMilli())
        val message = repo.newChatMessage(MessageRole.USER, fact, MessageStatus.SENT)
        repo.saveMessage(message)
        val now = clock.nowMillis()
        repo.upsertLong(LongTermMemory("fact-$day", fact, "general", 0.8, 0.5, now, now, null, MemorySource.USER_EXPLICIT, message.id,
            null, now, null, MemoryStatus.ACTIVE, null, repo.deviceId, 1, null))
        return "fact-$day"
    }

    @Test
    fun aDayEpisodeIsReplacedNotDuplicatedAndForgottenWithItsFacts() = runBlocking {
        val fact = talk("2026-10-27", "I'm moving to Berlin next month")
        assertTrue(episode("day", "2026-10-27").ok)
        assertTrue(episode("day", "2026-10-27", "Planned the Berlin move and worried about the visa").ok)

        val saved = repo.getLong("episode:day:2026-10-27")!!
        assertEquals(2L, saved.version)
        assertEquals(1, repo.allLong().count { it.category == "episode" })
        assertTrue("episode:day:2026-10-27" in HybridRanker(repo).search("Berlin visa").map { it.id })

        repo.forgetKnowledge(fact)
        assertEquals("", repo.getLong("episode:day:2026-10-27")!!.content)
        assertFalse(episode("day", "2026-10-27").ok)
    }

    @Test
    fun emptyAndFutureDaysAreRefused() = runBlocking {
        assertFalse(episode("day", "2026-10-20").ok)
        assertFalse(episode("day", "2026-10-28").ok)
        assertFalse(episode("week", "2026-10-21").ok)
    }

    @Test
    fun oldDaysRollUpIntoAWeekThatForgettingStillReaches() = runBlocking {
        for (day in listOf("2026-10-05", "2026-10-06", "2026-10-07")) {
            talk(day, "Something happened on $day")
            assertTrue(episode("day", day, "Notes for $day").ok)
        }
        talk("2026-10-27", "Back from the trip")
        val due = JSONObject(episode("day", "2026-10-27").payloadJson).getJSONArray("rollUpDue")
        assertEquals(1, due.length())
        assertEquals("2026-10-05", due.getJSONObject(0).getString("start"))
        assertEquals(3, due.getJSONObject(0).getJSONArray("episodes").length())

        val rolled = episode("week", "2026-10-05", "A busy first week of October")
        assertTrue(rolled.payloadJson, rolled.ok)
        assertEquals(MemoryStatus.ARCHIVED, repo.getLong("episode:day:2026-10-06")!!.status)
        val recalled = HybridRanker(repo).search("first week of October notes").map { it.id }
        assertTrue("episode:week:2026-10-05" in recalled)
        assertFalse("episode:day:2026-10-06" in recalled)
        assertEquals(0, JSONObject(episode("day", "2026-10-27").payloadJson).getJSONArray("rollUpDue").length())
        assertTrue("a retried roll-up still succeeds", episode("week", "2026-10-05", "A busy first week of October").ok)

        repo.forgetKnowledge("fact-2026-10-06")
        assertEquals("", repo.getLong("episode:day:2026-10-06")!!.content)
        assertEquals("", repo.getLong("episode:week:2026-10-05")!!.content)
        assertEquals("Notes for 2026-10-05", repo.getLong("episode:day:2026-10-05")!!.content)
    }

    @Test
    fun onlyTheNightlyReviewWritesEpisodes() {
        assertTrue(tools.specsFor(AgentRunType.CHAT).none { it.name == "save_episode" })
        assertTrue(tools.specsFor(AgentRunType.NIGHTLY).any { it.name == "save_episode" })
    }
}
