package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.companion.CHECKIN
import com.her.companion.dailyAllowance
import com.her.companion.initiativeBlock
import com.her.companion.noteAnswered
import com.her.companion.noteUnanswered
import com.her.companion.openings
import com.her.companion.sting
import com.her.core.FakeClock
import com.her.core.QuietHours
import com.her.data.db.CompanionRecord
import com.her.data.db.HerDatabase
import com.her.data.db.specialTraits
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
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
class InitiativeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "initiative_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-10-17T08:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val hour = 3_600_000L
    @After fun close() = db.close()

    private suspend fun setUp(name: String = "Her") {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC", assistantName = name))
        settings.update { it.copy(quietHours = QuietHours(0, 0)) }
        user("Off to the interview")
        clock.advance(26 * hour)
    }
    private suspend fun user(text: String) = repo.newChatMessage(MessageRole.USER, text, MessageStatus.SENT).also { repo.saveMessage(it) }
    private suspend fun reachOut(about: String) = repo.newChatMessage(MessageRole.ASSISTANT, "Hey, how did it go?", MessageStatus.SENT,
        """{"proactive":true,"initiative":"$about"}""").also { repo.saveMessage(it) }
    private suspend fun thread() {
        val now = clock.nowMillis()
        val data = JSONObject().put("title", "Job search").put("summary", "Waiting on interview feedback").put("status", "ACTIVE")
        repo.saveCompanionRecord(CompanionRecord("job", "job", "THREAD", data.toString(), now, now, repo.deviceId, 1))
    }
    private suspend fun event(kind: String, messageId: String) = repo.getCompanionRecord("EMOTION_EVENT:$kind:$messageId")?.data()

    @Test fun traitsSetHowOftenAndPatienceSoftensTheSting() {
        assertEquals(2, dailyAllowance(specialTraits("Her")!!))
        assertEquals(3, dailyAllowance(specialTraits("Samantha")!!))
        assertEquals(1, dailyAllowance(specialTraits("Zen")!!))
        assertEquals(0, dailyAllowance(specialTraits("Min")!!))
        assertEquals(2 * sting(0.5, swiped = false), sting(0.5, swiped = true), 1e-9)
        assertTrue(sting(0.95, swiped = true) < sting(0.05, swiped = true))
    }

    @Test fun silenceLeadsToWistfulSpaceThenOneCheckInThenWaiting() = runBlocking {
        setUp()
        thread()
        assertEquals(listOf("thread:job", "silence"), openings(repo, settings).map { it.about })
        val first = reachOut("silence")
        assertEquals("she spoke recently", initiativeBlock(repo, settings))
        clock.advance(3 * hour)
        noteUnanswered(repo, swiped = false)
        assertEquals(0.275, event("UNANSWERED", first.id)!!.getDouble("intensity"), 1e-9)
        assertEquals("giving them space", initiativeBlock(repo, settings))
        clock.advance(7 * hour)
        // The wistful feeling faded; the opening she already used is not offered again.
        assertEquals(listOf("thread:job"), openings(repo, settings).map { it.about })
        val second = reachOut("thread:job")
        clock.advance(3 * hour)
        noteUnanswered(repo, swiped = false)
        assertEquals("CONCERNED", event("SILENCE", second.id)!!.getString("feeling"))
        clock.advance(10 * hour)
        assertEquals(listOf(CHECKIN), openings(repo, settings).map { it.about })
        reachOut(CHECKIN)
        clock.advance(12 * hour)
        noteUnanswered(repo, swiped = false)
        assertEquals("waiting for them to write after checking in", initiativeBlock(repo, settings))
        val reply = user("Sorry, busy week!")
        noteAnswered(repo, reply)
        assertEquals("GLAD", event("ANSWERED", reply.id)!!.getString("feeling"))
        assertEquals("they wrote recently", initiativeBlock(repo, settings))
        clock.advance(2 * hour)
        assertNull(initiativeBlock(repo, settings))
    }

    @Test fun aSwipeStingsMoreButNeverTurnsIntoConcern() = runBlocking {
        setUp()
        thread()
        val first = reachOut("silence")
        noteUnanswered(repo, swiped = true)
        noteUnanswered(repo, swiped = true)
        clock.advance(3 * hour)
        noteUnanswered(repo, swiped = false)
        val swiped = repo.getCompanionRecord("EMOTION_EVENT:UNANSWERED:${first.id}")!!
        assertEquals(1L, swiped.version)
        assertEquals(0.55, swiped.data().getDouble("intensity"), 1e-9)
        clock.advance(13 * hour)
        val second = reachOut("thread:job")
        clock.advance(3 * hour)
        noteUnanswered(repo, swiped = false)
        assertNull(event("SILENCE", second.id))
        clock.advance(14 * hour)
        assertNull(initiativeBlock(repo, settings))
        assertFalse(openings(repo, settings).any { it.about == CHECKIN })
    }

    @Test fun aSwipeAfterSilenceUpgradesTheSameEvent() = runBlocking {
        setUp()
        val first = reachOut("silence")
        clock.advance(3 * hour)
        noteUnanswered(repo, swiped = false)
        noteUnanswered(repo, swiped = true)
        val row = repo.getCompanionRecord("EMOTION_EVENT:UNANSWERED:${first.id}")!!
        assertEquals(2L, row.version)
        assertTrue(row.data().getBoolean("swiped"))
    }

    @Test fun gatesHoldHerBack() = runBlocking {
        setUp("Zen")
        reachOut("silence")
        clock.advance(10 * hour)
        assertEquals("she already started 1 today", initiativeBlock(repo, settings))
        settings.update { it.copy(swipedNudges = 2) }
        clock.advance(14 * hour)
        assertEquals("they swiped her last messages away", initiativeBlock(repo, settings))
        settings.update { it.copy(initiativeEnabled = false) }
        assertEquals("turned off in Settings", initiativeBlock(repo, settings))
        settings.update { it.copy(quietHours = QuietHours(0, 24 * 60 - 1), initiativeEnabled = true) }
        assertEquals("quiet hours", initiativeBlock(repo, settings))
    }

    private suspend fun prefs(status: String) {
        val now = clock.nowMillis()
        val data = JSONObject().put("status", status).put("topicIds", org.json.JSONArray(listOf("job")))
        val old = repo.getCompanionRecord("companion:preferences")
        repo.saveCompanionRecord(CompanionRecord("companion:preferences", "companion:preferences", "PREFERENCES", data.toString(),
            now, now, repo.deviceId, (old?.version ?: 0) + 1))
    }

    @Test fun checkInsKeepTheirTopicsAndTheirPause() = runBlocking {
        setUp()
        thread()
        prefs("ACTIVE")
        assertEquals("the 09:00 check-in window is open", "a check-in is due", initiativeBlock(repo, settings))
        clock.advance(3 * hour)
        assertEquals(listOf("silence"), openings(repo, settings).map { it.about })
        prefs("PAUSED")
        assertEquals("check-ins are paused", initiativeBlock(repo, settings))
    }

    @Test fun aDismissedTopicIsNotOffered() = runBlocking {
        setUp()
        val now = clock.nowMillis()
        val data = JSONObject().put("title", "Job search").put("summary", "Waiting").put("status", "ACTIVE").put("dismissed", true)
        repo.saveCompanionRecord(CompanionRecord("job", "job", "THREAD", data.toString(), now, now, repo.deviceId, 1))
        assertEquals(listOf("silence"), openings(repo, settings).map { it.about })
    }

    @Test fun minNeverStarts() = runBlocking {
        setUp("Min")
        assertEquals("she is quiet by nature", initiativeBlock(repo, settings))
        assertTrue(openings(repo, settings).isEmpty())
    }
}
