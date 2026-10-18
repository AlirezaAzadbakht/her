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
import com.her.domain.MessageStatus
import com.her.data.db.CompanionRecord
import com.her.data.db.LearningRecord
import com.her.domain.Feeling
import com.her.domain.Reminder
import com.her.domain.ReminderRepeat
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import com.her.feelings.emotionalState
import com.her.feelings.rankSupportLessons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EmotionalStateTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "emotional_${System.nanoTime()}")
    private val clock = FakeClock(1_800_000_000_000L, tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    @After fun close() = db.close()
    private suspend fun source(text: String = "Your explanation helped me. I am still sad."): JSONObject {
        val m = repo.newChatMessage(MessageRole.USER, text, MessageStatus.SENT)
        repo.saveMessage(m)
        return JSONObject().put("sourceMessageId", m.id).put("evidence", text)
    }
    private suspend fun event(): JSONObject = source().put("event", "BENEFIT_REPORTED").put("interpretation", "Explanation was useful; sadness remains")
        .put("feeling", "TENDER").put("intensity", 0.8).put("confidence", 0.9)
    private suspend fun call(name: String, a: JSONObject): JSONObject {
        val r = tools.execute(name, a.toString()); assertTrue(r.payloadJson, r.ok); return JSONObject(r.payloadJson)
    }
    @Test fun benefitIsIndependentAndRetriesAndReflectionDoNotRewardAgain() = runBlocking {
        val a = event()
        val saved = call("record_emotional_experience", a)
        val before = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertTrue(before.purpose.getValue("fulfillment") > 0.5)
        assertEquals(Feeling.TENDER, repo.observeFeeling().first()?.feeling)
        assertEquals(saved.toString(), call("record_emotional_experience", a).toString())
        call("save_emotional_reflection", JSONObject().put("id", "today").put("eventIds", JSONArray(listOf(saved.getString("id"))))
            .put("helped", "Explanation").put("misunderstood", "Unknown").put("uncertain", "Whether sadness changed").put("adjustment", "Keep listening"))
        assertEquals(before, emotionalState(repo.companionRecords(), clock.nowMillis()))
        clock.advance(90L * 24 * 3_600_000)
        val later = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertEquals(0.5, later.purpose.getValue("fulfillment"), 0.001)
        assertEquals(Feeling.CALM, later.dominant)
    }
    @Test fun blankOptionalIdListsExistingEventsForBackgroundReview() = runBlocking {
        val id = call("record_emotional_experience", event()).getString("id")
        val result = call("get_emotional_state", JSONObject().put("id", "").put("offset", 0))
        assertEquals(id, result.getJSONArray("records").getJSONObject(0).getString("id"))
        assertEquals(1, call("get_emotional_state", JSONObject().put("id", id)).getJSONArray("records").length())
        assertEquals(0, call("get_emotional_state", JSONObject().put("id", "missing")).getJSONArray("records").length())
    }

    @Test fun fabricatedEvidenceInvalidNumbersAndUnverifiedExecutionAreRejected() = runBlocking {
        val a = event().put("evidence", "This quote does not exist")
        assertFalse(tools.execute("record_emotional_experience", a.toString()).ok)
        assertFalse(tools.execute("record_emotional_experience", event().put("confidence", 1.1).toString()).ok)
        assertFalse(tools.execute("record_emotional_experience", event().put("event", "ACTION_SUCCEEDED").put("taskId", "missing").toString()).ok)
        assertTrue(repo.companionRecords().isEmpty())
    }
    @Test fun correctionsRequireVersionsAndForgettingRemovesDerivedLessons() = runBlocking {
        val a = event()
        val id = call("record_emotional_experience", a).getString("id")
        a.put("interpretation", "More cautious interpretation")
        assertFalse(tools.execute("record_emotional_experience", a.toString()).ok)
        call("record_emotional_experience", a.put("expectedVersion", 1))
        val reflection = call("save_emotional_reflection", JSONObject().put("id", "today").put("eventIds", JSONArray(listOf(id)))
            .put("helped", "Explanation").put("misunderstood", "Unknown").put("uncertain", "Outcome").put("adjustment", "Listen"))
        repo.forgetCompanion(id)
        assertNotNull(repo.getCompanionRecord(reflection.getString("id"))?.deletedAt)
        assertFalse(tools.execute("record_emotional_experience", a.toString()).ok)
        assertEquals(0.5, emotionalState(repo.companionRecords(), clock.nowMillis()).purpose.getValue("fulfillment"), 0.0)
    }
    @Test fun understandingSeparatesStatedFeelingFromTentativeSupportAndCanBeCorrected() = runBlocking {
        val a = source("I feel sad. Please just listen.").put("id", "work").put("confidence", 0.7)
            .put("situation", "Work stress").put("userFeeling", "sad").put("interpretation", "May want space")
            .put("supportApproach", "Listen first").put("exceptions", "Offer plans when requested")
        val id = call("save_emotional_understanding", a).getString("id")
        assertEquals("sad", repo.getCompanionRecord(id)?.data()?.getString("userFeeling"))
        assertFalse(tools.execute("save_emotional_understanding", a.put("userFeeling", "angry").toString()).ok)
        a.put("userFeeling", "sad").put("expectedVersion", 1).put("confidence", 0.8)
        assertEquals(2L, call("save_emotional_understanding", a).getLong("version"))
    }
    @Test fun offersDoNotEarnFulfillmentAndMixedFeelingsSurviveSerialization() = runBlocking {
        val offer = event().put("event", "HELP_OFFERED").put("feeling", "CURIOUS")
        call("record_emotional_experience", offer)
        var state = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertEquals(0.5, state.purpose.getValue("fulfillment"), 0.0)
        call("record_emotional_experience", event().put("feeling", "CONCERNED"))
        val rows = repo.companionRecords()
        state = emotionalState(rows, clock.nowMillis())
        assertTrue(state.emotions.getValue(Feeling.CURIOUS) > 0)
        assertTrue(state.emotions.getValue(Feeling.CONCERNED) > 0)
        assertEquals(state, emotionalState(rows.map { com.her.data.db.CompanionRecord.fromJson(JSONObject(it.toJson())) }, clock.nowMillis()))
        val stale = rows.map { it.copy(content = it.data().put("stale", true).toString()) }
        assertEquals(Feeling.CALM, emotionalState(stale, clock.nowMillis()).dominant)
        assertEquals(0.5, emotionalState(stale, clock.nowMillis()).purpose.getValue("fulfillment"), 0.0)
    }

    @Test fun completedActionBuildsCompetenceWithoutAssumingBenefit() = runBlocking {
        val id = call("create_task", JSONObject().put("title", "Prepare an explanation")).getString("id")
        val task = repo.getTask(id)!!
        repo.saveTask(task.copy(status = com.her.domain.TaskStatus.DONE))
        val a = JSONObject().put("event", "ACTION_SUCCEEDED").put("taskId", id)
            .put("interpretation", "Finished preparing the requested explanation")
            .put("feeling", "GLAD").put("intensity", 0.6).put("confidence", 1.0)
        call("record_emotional_experience", a)
        val state = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertTrue(state.purpose.getValue("competence") > 0.5)
        assertEquals(0.5, state.purpose.getValue("fulfillment"), 0.0)
        val contextBuilder = com.her.agent.prompt.ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context))
        assertTrue(contextBuilder.build().systemBundle.contains("ACTION_SUCCEEDED"))
    }

    @Test fun saturationNeverHitsOneAndCalmYields() = runBlocking {
        val now = clock.nowMillis()
        repeat(12) { i ->
            repo.saveCompanionRecord(eventRow("BENEFIT_REPORTED", "GLAD", "s$i", now, intensity = 1.0))
        }
        val state = emotionalState(repo.companionRecords(), now)
        assertTrue(state.emotions.getValue(Feeling.GLAD) < 1.0)
        assertTrue(state.purpose.getValue("fulfillment") < 1.0)
        assertTrue(state.emotions.getValue(Feeling.CALM) < state.emotions.getValue(Feeling.GLAD))
        assertEquals(Feeling.GLAD, state.dominant)
        val mixed = emotionalState(listOf(eventRow("BENEFIT_REPORTED", "GLAD", "one", now, intensity = 0.7)), now)
        assertEquals(listOf(Feeling.GLAD, Feeling.CALM), mixed.blend().map { it.first })
    }

    @Test fun traitsStayWithinBoundsAndGuidanceFollowsThresholds() = runBlocking {
        val now = clock.nowMillis()
        repeat(20) { i ->
            repo.saveCompanionRecord(eventRow("UNRESOLVED_QUESTION", "CURIOUS", "q$i", now))
        }
        val curious = emotionalState(repo.companionRecords(), now)
        assertTrue(curious.disposition.getValue("curiosity") > 0.6)
        assertTrue(curious.disposition.getValue("curiosity") < 0.7)
        assertEquals(0.7, curious.disposition.getValue("warmth"), 0.0)
        assertEquals(0.8, curious.disposition.getValue("patience"), 0.0)
        repo.companionRecords().forEach { repo.forgetCompanion(it.id) }
        repeat(3) { i ->
            repo.saveCompanionRecord(eventRow("MISUNDERSTANDING", "CURIOUS", "m$i", now, intensity = 0.4))
        }
        val lost = emotionalState(repo.companionRecords(), now)
        assertTrue(lost.purpose.getValue("understanding") < 0.4)
        assertTrue(lost.purpose.getValue("competence") < 0.4)
        assertTrue(lost.disposition.getValue("patience") > 0.8)
        assertTrue(lost.disposition.getValue("patience") < 0.9)
        val hints = lost.guidance()
        assertTrue(hints.any { it.contains("clarifying") })
        assertTrue(hints.any { it.contains("confirm details") })
        val bundle = com.her.agent.prompt.ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context)).build().systemBundle
        assertTrue(bundle.contains("Tone from modeled state"))
        assertTrue(bundle.contains("ask one clarifying question before advising"))
    }

    @Test fun deadlineConcernGrowsThenClearsWhenDone() = runBlocking {
        val now = clock.nowMillis()
        val soon = now + 60 * 60 * 1000L
        val later = now + 36L * 60 * 60 * 1000
        val rising = emotionalState(emptyList(), now + 50 * 60 * 1000L, listOf(soon))
        val early = emotionalState(emptyList(), now, listOf(soon))
        assertTrue(early.emotions.getValue(Feeling.CONCERNED) > 0)
        assertTrue(rising.emotions.getValue(Feeling.CONCERNED) > early.emotions.getValue(Feeling.CONCERNED))
        assertEquals(Feeling.CONCERNED, rising.dominant)
        assertTrue(rising.guidance().any { it.contains("steady") })
        assertEquals(0.0, emotionalState(emptyList(), now, listOf(later)).emotions[Feeling.CONCERNED] ?: 0.0, 0.0)
        val id = call("create_task", JSONObject().put("title", "File the report").put("dueAt", "in 1 hour")).getString("id")
        val open = emotionalState(repo.companionRecords(), clock.nowMillis(), repo.openDueAts())
        assertTrue(open.emotions.getValue(Feeling.CONCERNED) > 0)
        assertEquals(Feeling.CONCERNED, repo.observeFeeling().first()?.feeling)
        repo.saveTask(repo.getTask(id)!!.copy(status = com.her.domain.TaskStatus.DONE))
        assertEquals(0.0, emotionalState(repo.companionRecords(), clock.nowMillis(), repo.openDueAts()).emotions[Feeling.CONCERNED] ?: 0.0, 0.0)
        assertNull(repo.observeFeeling().first())
    }

    @Test fun supportLessonsRankBySituationAndQuery() = runBlocking {
        val listen = source("For this work presentation I feel sad. Please listen first.").put("id", "work")
            .put("confidence", 0.8).put("situation", "Work presentation").put("userFeeling", "sad")
            .put("interpretation", "Wants space").put("supportApproach", "Listen first").put("exceptions", "Advice only if asked")
        val cook = source("I feel anxious about cooking tonight. Please suggest recipes.").put("id", "cook")
            .put("confidence", 0.8).put("situation", "Weeknight cooking").put("userFeeling", "anxious")
            .put("interpretation", "Wants ideas").put("supportApproach", "Offer recipes").put("exceptions", "Skip if they want to rest")
        call("save_emotional_understanding", listen)
        call("save_emotional_understanding", cook)
        val now = clock.nowMillis()
        val ranked = rankSupportLessons(repo.companionRecords(), "another work presentation and I feel sad", now)
        assertEquals("EMOTION_UNDERSTANDING:work", ranked.first().id)
        val queried = call("get_emotional_state", JSONObject().put("query", "work presentation sad"))
        assertEquals("EMOTION_UNDERSTANDING:work", queried.getJSONArray("records").getJSONObject(0).getString("id"))
        val bundle = com.her.agent.prompt.ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context))
            .build("I have another work presentation and I feel sad").systemBundle
        assertTrue(bundle.contains("Support lessons that may apply now"))
        assertTrue(bundle.indexOf("Listen first") < bundle.indexOf("Offer recipes"))
    }

    @Test fun reminderAndLearningActionsNeedVerifiedSourcesAndSharingDoesNotReward() = runBlocking {
        val scheduled = reminder(ReminderStatus.SCHEDULED)
        assertFalse(tools.execute("record_emotional_experience", action("reminder", scheduled).toString()).ok)
        val fired = reminder(ReminderStatus.FIRED)
        call("record_emotional_experience", action("reminder", fired))
        val afterReminder = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertTrue(afterReminder.purpose.getValue("competence") > 0.5)
        assertEquals(0.5, afterReminder.purpose.getValue("fulfillment"), 0.0)
        val attempt = learning("ATTEMPT", JSONObject().put("result", "INCORRECT"))
        assertFalse(tools.execute("record_emotional_experience", action("learning", attempt).toString()).ok)
        val progress = learning("ATTEMPT", JSONObject().put("result", "CORRECT"))
        call("record_emotional_experience", action("learning", progress))
        val path = learning("PATH", JSONObject().put("status", "ACTIVE"))
        assertFalse(tools.execute("record_emotional_experience", action("learning", path).toString()).ok)
        val shared = source("I started a small garden on the balcony.").put("event", "USER_SHARED")
            .put("interpretation", "They shared something personal").put("feeling", "CURIOUS").put("intensity", 0.5).put("confidence", 0.8)
        call("record_emotional_experience", shared)
        val state = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertEquals(0.5, state.purpose.getValue("fulfillment"), 0.0)
        assertTrue(state.emotions.getValue(Feeling.CURIOUS) > 0)
        val inspected = call("get_emotional_state", JSONObject())
        assertTrue(inspected.getJSONArray("firedReminders").length() >= 1)
        assertTrue(inspected.getJSONArray("learningProgress").length() >= 1)
        assertTrue(state.purpose.getValue("competence") > afterReminder.purpose.getValue("competence"))
    }

    private fun eventRow(event: String, feeling: String, source: String, now: Long, intensity: Double = 0.8) =
        CompanionRecord("EMOTION_EVENT:$event:$source", "EMOTION_EVENT:$event:$source", "EMOTION_EVENT",
            JSONObject().put("event", event).put("feeling", feeling).put("intensity", intensity).put("confidence", 1.0).put("occurredAt", now).toString(),
            now, now, repo.deviceId, 1)

    private suspend fun reminder(status: ReminderStatus): String {
        val now = clock.nowMillis()
        val id = "reminder-$status-${System.nanoTime()}"
        repo.saveReminder(Reminder(id, "Drink water", ReminderTrigger.TIME, now + 3_600_000, ReminderRepeat.NONE, null, null, null, null, status, if (status == ReminderStatus.FIRED) now else null, null, now, now, repo.deviceId, 1, null))
        return id
    }

    private suspend fun learning(kind: String, data: JSONObject): String {
        val now = clock.nowMillis()
        val id = "learn:$kind:${System.nanoTime()}"
        repo.saveLearningRecord(LearningRecord(id, "path:x", kind, data.toString(), now, now, repo.deviceId, 1, null))
        return id
    }

    private fun action(kind: String, id: String) = JSONObject().put("event", "ACTION_SUCCEEDED").put("actionKind", kind).put("actionId", id)
        .put("interpretation", "Verified $kind completed").put("feeling", "GLAD").put("intensity", 0.6).put("confidence", 1.0)
}
