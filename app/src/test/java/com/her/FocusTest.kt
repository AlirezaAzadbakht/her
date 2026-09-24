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
import com.her.domain.MessageRole
import com.her.focus.FocusAlarm
import com.her.focus.FocusSession
import com.her.focus.FocusTimer
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MINUTE = 60 * 1000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class FocusTest {
    private lateinit var db: HerDatabase
    private lateinit var settings: AppSettingsStore
    private lateinit var repo: HerRepository
    private lateinit var timer: FocusTimer
    private lateinit var tools: ToolRegistry
    private val clock = FakeClock(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli(), tickMs = 0)
    private val alarm = FakeFocusAlarm()
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private val nudges = mutableListOf<String>()

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        settings = AppSettingsStore(context, "focus_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, clock)
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val notifier = object : Notifier(context) {
            override fun showFocus(session: FocusSession, text: String?, alert: Boolean) {
                shown += (text ?: session.label) to alert
            }

            override fun clearFocus() = Unit

            override fun show(text: String, briefing: Boolean) {
                nudges += text
            }
        }
        timer = FocusTimer(settings, repo, notifier, alarm)
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient(), focus = timer)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun phasesRunWorkBreakWorkAndEndOnWork() {
        var session: FocusSession? = FocusSession("Report", null, 25, 5, 3, phase = 0, phaseEndsAt = 0, startedAt = 0)
        val kinds = mutableListOf<String>()
        while (session != null) {
            kinds += if (session.isWork) "work ${session.round}" else "break"
            session = session.next(0)
        }
        assertEquals(listOf("work 1", "break", "work 2", "break", "work 3"), kinds)
        assertEquals(0, FocusSession("x", null, 25, 5, 3, 0, 0, 0).completedRounds)
        assertEquals(1, FocusSession("x", null, 25, 5, 3, 1, 0, 0).completedRounds)
        val saved = FocusSession("x", "t1", 25, 5, 3, 2, 42, 7, breakMessage = "Stretch")
        assertEquals(saved, FocusSession.fromJson(saved.toJson()))
    }

    @Test
    fun aWholeSessionRingsEachPhaseAndIsRemembered() = runBlocking {
        timer.start("Report", null, 25, 5, 2, "Stretch for five.", "Back to the report.", "Two rounds done.")
        assertEquals(listOf("Report" to false), shown)
        assertEquals(clock.peek() + 25 * MINUTE, alarm.armedAt)

        clock.advance(10 * MINUTE)
        timer.onAlarm()
        assertEquals("an early ring changes nothing", 0, settings.read().focus!!.phase)

        clock.advance(15 * MINUTE)
        timer.onAlarm()
        assertEquals("Stretch for five." to true, shown.last())
        assertEquals(clock.peek() + 5 * MINUTE, alarm.armedAt)

        clock.advance(5 * MINUTE)
        timer.onAlarm()
        assertEquals("Back to the report." to true, shown.last())

        clock.advance(25 * MINUTE)
        timer.onAlarm()
        assertNull(settings.read().focus)
        assertTrue(alarm.cancelled)
        assertEquals(listOf("Two rounds done."), nudges)
        assertEquals("Two rounds done.", repo.recentMessages(10).last { it.role == MessageRole.ASSISTANT }.content)
        assertTrue(memories().single().contains("Focused 2 × 25 min on “Report”"))
    }

    @Test
    fun stoppingKeepsOnlyFinishedRounds() = runBlocking {
        timer.start("Inbox", null, 25, 5, 4, null, null, null)
        timer.stop()
        assertTrue("nothing finished, nothing remembered", memories().isEmpty())

        timer.start("Inbox", null, 25, 5, 4, null, null, null)
        clock.advance(25 * MINUTE)
        timer.onAlarm()
        timer.stop()
        assertTrue(memories().single().contains("Focused 1 × 25 min"))
        assertTrue("a stop says nothing on its own", nudges.isEmpty())
    }

    @Test
    fun nudgesWaitWhileTheyWorkButNotDuringABreak() = runBlocking {
        val policy = NotificationPolicy(settings)
        val now = Instant.ofEpochMilli(clock.peek()).atZone(ZoneId.of("UTC"))
        timer.start("Report", null, 25, 5, 2, null, null, null)
        assertEquals("focusing", policy.shouldNotify("k", urgent = false, now = now).reason)

        clock.advance(25 * MINUTE)
        timer.onAlarm()
        assertTrue(policy.shouldNotify("k", urgent = false, now = now).notify)
    }

    @Test
    fun startFocusLinksTheTaskAndChecksItsNumbers() = runBlocking {
        tools.execute("create_task", """{"title":"Quarterly report"}""")
        val result = tools.execute("start_focus", """{"task":"quarterly report","minutes":50,"rounds":2}""")
        assertTrue(result.payloadJson, result.ok)
        val payload = JSONObject(result.payloadJson)
        assertEquals("Quarterly report", payload.getString("label"))
        assertEquals("exact", payload.getString("timing"))
        val session = settings.read().focus!!
        assertEquals(50, session.workMin)
        assertEquals(5, session.breakMin)

        assertFalse(tools.execute("start_focus", """{"label":"x","minutes":500}""").ok)
        assertTrue(tools.execute("stop_focus", "{}").ok)
        assertFalse("nothing left to stop", tools.execute("stop_focus", "{}").ok)
    }

    private suspend fun memories() = repo.activeShort().map { it.content }

    private class FakeFocusAlarm : FocusAlarm {
        var armedAt: Long? = null
        var cancelled = false

        override fun arm(at: Long) {
            armedAt = at
            cancelled = false
        }

        override fun cancel() {
            armedAt = null
            cancelled = true
        }

        override fun exactAllowed(): Boolean = true
    }
}
