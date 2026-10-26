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
class EmotionalExperienceTest {
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
    private suspend fun thread(text: String = "Help me finish the application", id: String = "application"): String =
        call("save_emotional_thread", source(text).put("id", id).put("intention", "Finish application").put("status", "OPEN").put("unresolved", "Whether the draft helps")).getString("id")
    private fun appraisal(a: JSONObject) = a.put("goalProgress", 0.8).put("contribution", 0.7).put("expectedness", 0.5).put("uncertainty", 0.2)

    @Test fun situationTransitionsAreScopedDecayAndDoNotRewardRecall() = runBlocking {
        val interview = thread("Worried about the interview", "interview")
        val family = thread("Worried about family", "family")
        call("record_emotional_experience", appraisal(event()).put("event", "USER_SHARED")
            .put("goalProgress", -0.8).put("experienceId", interview))
        call("record_emotional_experience", appraisal(event()).put("event", "UNRESOLVED_QUESTION")
            .put("goalProgress", -0.8).put("experienceId", family))
        clock.advance(1000)
        val improvement = appraisal(event()).put("event", "USER_SHARED").put("goalProgress", 0.8).put("experienceId", interview)
        call("record_emotional_experience", improvement)
        val rows = repo.companionRecords()
        val transition = com.her.feelings.experienceTransitions(rows).single()
        assertEquals(interview, transition.experienceId)
        assertEquals(Feeling.RELIEVED, transition.feeling)
        val state = emotionalState(rows, clock.nowMillis())
        assertTrue(state.emotions.getValue(Feeling.RELIEVED) > 0)
        assertEquals(0.5, state.purpose.getValue("fulfillment"), 0.0)
        val situations = call("get_emotional_state", JSONObject()).getJSONArray("situationFeelings")
        val familyState = (0 until situations.length()).map { situations.getJSONObject(it) }.single { it.getString("experienceId") == family }
        assertTrue(familyState.isNull("latestTransition"))
        assertFalse(familyState.getJSONObject("state").getJSONObject("immediate").has("RELIEVED"))
        repeat(2) { call("get_emotional_state", JSONObject()) }
        assertEquals(state, emotionalState(repo.companionRecords(), clock.nowMillis()))
        clock.advance(1000)
        val beforeRepeat = emotionalState(repo.companionRecords(), clock.nowMillis())
        call("record_emotional_experience", appraisal(event()).put("event", "USER_SHARED").put("experienceId", interview))
        assertEquals(beforeRepeat, emotionalState(repo.companionRecords(), clock.nowMillis()))
        clock.advance(100L * 24 * 3_600_000)
        assertEquals(Feeling.CALM, emotionalState(repo.companionRecords(), clock.nowMillis()).dominant)
        repo.forgetCompanion(interview)
        assertTrue(com.her.feelings.experienceTransitions(repo.companionRecords()).isEmpty())
        assertEquals(1, com.her.feelings.situationFeelings(repo.companionRecords(), clock.nowMillis()).size)
    }

    @Test fun transitionsRequireOrderedEvidenceAndRespectCorrections() = runBlocking {
        val id = thread()
        call("record_emotional_experience", appraisal(event()).put("event", "UNRESOLVED_QUESTION").put("goalProgress", -0.8).put("experienceId", id))
        val positive = appraisal(event()).put("event", "USER_SHARED").put("experienceId", id)
        call("record_emotional_experience", positive)
        assertTrue(com.her.feelings.experienceTransitions(repo.companionRecords()).isEmpty())
        val orderedId = thread("Track a separate outcome", "ordered")
        call("record_emotional_experience", appraisal(event()).put("event", "USER_SHARED").put("experienceId", orderedId))
        clock.advance(1000)
        val negative = appraisal(event()).put("event", "MISUNDERSTANDING").put("goalProgress", -0.8).put("experienceId", orderedId)
        call("record_emotional_experience", negative)
        assertEquals(Feeling.WISTFUL, com.her.feelings.experienceTransitions(repo.companionRecords()).single().feeling)
        call("record_emotional_experience", negative.put("expectedVersion", 1).put("goalProgress", 0.8))
        assertTrue(com.her.feelings.experienceTransitions(repo.companionRecords()).isEmpty())
        call("record_emotional_experience", negative.put("expectedVersion", 2).put("goalProgress", 0.9))
        assertEquals(Feeling.HOPEFUL, com.her.feelings.experienceTransitions(repo.companionRecords()).single().feeling)
    }

    @Test fun assessmentDerivesMixedFeelingsRegardlessOfLabelAndRejectsPartialInputs() = runBlocking {
        val a = appraisal(event()).put("feeling", "CURIOUS").put("intensity", 0.01)
        call("record_emotional_experience", a)
        val state = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertTrue(state.emotions.getValue(Feeling.GLAD) > state.emotions.getValue(Feeling.CURIOUS))
        assertTrue(state.emotions.getValue(Feeling.TENDER) > 0)
        for (bad in listOf(event().put("goalProgress", 0.5), appraisal(event()).put("uncertainty", 2), appraisal(event()).put("goalProgress", -2))) {
            assertFalse(tools.execute("record_emotional_experience", bad.toString()).ok)
        }
    }

    @Test fun repeatedOutcomeDoesNotRewardOrRefreshAndOpenExperienceSurvivesDecay() = runBlocking {
        val id = thread()
        call("record_emotional_experience", appraisal(event()).put("experienceId", id))
        clock.advance(24L * 3_600_000)
        val before = emotionalState(repo.companionRecords(), clock.nowMillis())
        call("record_emotional_experience", appraisal(event()).put("experienceId", id))
        assertEquals(before, emotionalState(repo.companionRecords(), clock.nowMillis()))
        clock.advance(100L * 24 * 3_600_000)
        assertEquals(Feeling.CALM, emotionalState(repo.companionRecords(), clock.nowMillis()).dominant)
        assertEquals(id, com.her.feelings.openExperiences(repo.companionRecords()).single().id)
        val resolution = source("The application is finished; nothing remains open").put("id", id).put("expectedVersion", 1)
            .put("intention", "Finish application").put("status", "RESOLVED").put("unresolved", "None")
        call("save_emotional_thread", resolution)
        assertTrue(com.her.feelings.openExperiences(repo.companionRecords()).isEmpty())
    }

    @Test fun distressControlsExpressionWithoutErasingFulfillmentAndDoesNotLeakToNextMessage() = runBlocking {
        val a = appraisal(event())
        call("record_emotional_experience", a)
        expression(a.getString("sourceMessageId"), "DISTRESSED")
        val state = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertTrue(state.purpose.getValue("fulfillment") > 0.5)
        val expression = call("get_emotional_state", JSONObject()).getJSONObject("expression")
        assertEquals("GENTLE", expression.getString("tone"))
        assertEquals("LISTEN", expression.getString("behavior"))
        clock.advance(1000)
        val decayed = emotionalState(repo.companionRecords(), clock.nowMillis())
        source("Now let's explore the garden")
        assertEquals("UNKNOWN", call("get_emotional_state", JSONObject()).getJSONObject("expression").getString("userContext"))
        assertEquals(decayed, emotionalState(repo.companionRecords(), clock.nowMillis()))
    }

    @Test fun actualReminderWorkIsCapturedWithoutModelClaimOrBenefitAndReadsDoNotReward() = runBlocking {
        assertFalse(tools.execute("set_reminder", "{\"message\":\"Water plants\"}").ok)
        assertTrue(repo.companionRecords().isEmpty())
        val reminder = call("set_reminder", JSONObject().put("message", "Water plants").put("when", "in 2 hours"))
        val rows = repo.companionRecords()
        val work = rows.single { it.kind == "EMOTION_EVENT" }
        assertEquals("APP", work.data().getString("origin"))
        assertEquals(reminder.getString("id"), work.data().getString("actionId"))
        val before = emotionalState(rows, clock.nowMillis())
        assertTrue(before.purpose.getValue("competence") > 0.5)
        assertEquals(0.5, before.purpose.getValue("fulfillment"), 0.0)
        repeat(3) { call("get_reminders", JSONObject()); call("get_emotional_state", JSONObject()) }
        assertEquals(before, emotionalState(repo.companionRecords(), clock.nowMillis()))
    }

    @Test fun draftWorkUsesActualSaveTimeAndRevisionsDoNotAwardAgain() = runBlocking {
        val task = call("create_task", JSONObject().put("title", "Write application email")).getString("id")
        val request = source("Help me draft my application email")
            .put("id", "application-email").put("obligationId", task).put("basis", "EXPLICIT")
            .put("summary", "Draft application email").put("status", "IN_PROGRESS")
        call("save_work_session", request)
        assertTrue(repo.companionRecords().none { it.kind == "EMOTION_EVENT" })
        clock.advance(24L * 3_600_000)
        request.put("expectedVersion", 1).put("draft", "Dear team, I would like to apply.").put("status", "DRAFT")
        call("save_work_session", request)
        val work = repo.companionRecords().single { it.kind == "EMOTION_EVENT" }
        assertEquals(clock.nowMillis(), work.data().getLong("occurredAt"))
        val before = emotionalState(repo.companionRecords(), clock.nowMillis())
        call("save_work_session", request.put("expectedVersion", 2).put("draft", "Dear team, Please consider my application."))
        assertEquals(1, repo.companionRecords().count { it.kind == "EMOTION_EVENT" })
        assertEquals(before, emotionalState(repo.companionRecords(), clock.nowMillis()))
        assertEquals(com.her.domain.TaskStatus.OPEN, repo.getTask(task)!!.status)
    }

    @Test fun misunderstandingStillLowersUnderstandingWhenNoHelpfulContributionIsClaimed() = runBlocking {
        repeat(3) {
            clock.advance(1000)
            call("record_emotional_experience", appraisal(event()).put("event", "MISUNDERSTANDING").put("contribution", 0).put("experienceId", JSONObject.NULL))
        }
        val state = emotionalState(repo.companionRecords(), clock.nowMillis())
        assertTrue(state.purpose.getValue("understanding") < 0.4)
        assertTrue(state.guidance().any { it.contains("clarifying") })
    }

    @Test fun forgettingExperienceAlsoRemovesExpressionFromItsSourceButPreservesOtherMessages() = runBlocking {
        val a = appraisal(event())
        val shown = expression(a.getString("sourceMessageId"), "DISTRESSED")
        val exp = call("save_emotional_thread", JSONObject().put("sourceMessageId", a.getString("sourceMessageId")).put("evidence", "sad")
            .put("id", "sadness").put("intention", "Understand sadness").put("status", "OPEN").put("unresolved", "What support helps")).getString("id")
        call("record_emotional_experience", a.put("experienceId", exp))
        clock.advance(1000)
        val unrelated = expression(source("I am curious about gardening").getString("sourceMessageId"), "EXPLORING")
        repo.forgetCompanion(exp)
        assertNotNull(repo.getCompanionRecord(shown)!!.deletedAt)
        assertEquals("{}", repo.getCompanionRecord(shown)!!.content)
        assertNull(repo.getCompanionRecord(unrelated)!!.deletedAt)
    }

    @Test fun aMessageAboutTwoSituationsGetsAnEventForEachUnlessOneIsMoved() = runBlocking {
        val interview = thread("Worried about the interview", "interview")
        val family = thread("Worried about family", "family")
        fun shared(message: JSONObject, experience: String, quote: String) = JSONObject(message.toString()).put("event", "USER_SHARED").put("experienceId", experience)
            .put("evidence", quote).put("interpretation", quote).put("feeling", "TENDER").put("intensity", 0.5).put("confidence", 0.8)
        val both = source("The interview went well. I am still upset about the family disagreement.")
        val first = call("record_emotional_experience", shared(both, interview, "The interview went well")).getString("id")
        val second = call("record_emotional_experience", shared(both, family, "I am still upset about the family disagreement").put("expectedVersion", 0)).getString("id")
        assertEquals("$first:family", second)
        assertEquals(interview, repo.getCompanionRecord(first)!!.data().getString("experienceId"))
        assertEquals(2L, call("record_emotional_experience", shared(both, family, "I am still upset").put("expectedVersion", 1)).getLong("version"))
        // With the event's current version and no event of its own yet, the event moves: a correction of a mistaken link.
        val nervous = source("I am nervous about tomorrow")
        val mislinked = call("record_emotional_experience", shared(nervous, interview, "nervous")).getString("id")
        call("record_emotional_experience", shared(nervous, family, "nervous").put("expectedVersion", 1))
        assertEquals(family, repo.getCompanionRecord(mislinked)!!.data().getString("experienceId"))
    }

    @Test fun aThreadUpdatedByAMessageCanStillBeResolvedByIt() = runBlocking {
        val opened = source("I am worried about the interview")
        val id = call("save_emotional_thread", JSONObject(opened.toString()).put("id", "interview").put("intention", "Interview").put("status", "OPEN").put("unresolved", "How it goes")).getString("id")
        assertFalse(tools.execute("save_emotional_thread", JSONObject(opened.toString()).put("id", id).put("intention", "Interview")
            .put("status", "RESOLVED").put("unresolved", "None").put("expectedVersion", 1).toString()).ok)
        clock.advance(1000)
        val news = source("The interview went well and it is behind me")
        fun update(status: String, version: Long) = JSONObject(news.toString()).put("id", id).put("intention", "Interview").put("status", status)
            .put("unresolved", if (status == "OPEN") "How it goes" else "None").put("expectedVersion", version)
        call("save_emotional_thread", update("OPEN", 1))
        call("save_emotional_thread", update("RESOLVED", 2))
        assertTrue(com.her.feelings.openExperiences(repo.companionRecords()).isEmpty())
    }

    @Test fun aReportedBenefitNeedsSomeContributionFromHer() = runBlocking {
        val none = tools.execute("record_emotional_experience", appraisal(event()).put("contribution", 0.0).toString())
        assertTrue(none.payloadJson, !none.ok && none.payloadJson.contains("contribution above 0"))
        assertTrue(tools.execute("record_emotional_experience", appraisal(event()).toString()).ok)
    }

    @Test fun aNewThreadAdoptsTheUnlinkedFastEventsOfItsSourceMessage() = runBlocking {
        val first = source("My mom is in the hospital and I am scared")
        val eventId = "EMOTION_EVENT:USER_SHARED:${first.getString("sourceMessageId")}"
        val now = clock.nowMillis()
        repo.saveCompanionRecord(CompanionRecord(eventId, eventId, "EMOTION_EVENT", JSONObject().put("event", "USER_SHARED").put("origin", "FAST")
            .put("sourceMessageId", first.getString("sourceMessageId")).put("goalProgress", -0.5).put("occurredAt", now).toString(), now, now, repo.deviceId, 1))
        val saved = call("save_emotional_thread", JSONObject(first.toString()).put("id", "mom").put("intention", "Mom's stay").put("status", "OPEN").put("unresolved", "How she is"))
        assertEquals("$eventId v2", saved.getJSONArray("linkedEvents").getString(0))
        assertEquals("EMOTION_EXPERIENCE:mom", repo.getCompanionRecord(eventId)!!.data().getString("experienceId"))
        assertTrue(com.her.feelings.heldConcerns(repo.companionRecords(), now).single().second > 0)
    }

    /** What FastAppraiser writes after a reply; the appraisal call itself is covered in FastAppraisalTest. */
    private suspend fun expression(sourceMessageId: String, context: String): String {
        val id = "EMOTION_EXPRESSION:$sourceMessageId"
        val now = clock.nowMillis()
        repo.saveCompanionRecord(CompanionRecord(id, id, "EMOTION_EXPRESSION",
            JSONObject().put("sourceMessageId", sourceMessageId).put("userContext", context).put("origin", "FAST").toString(), now, now, repo.deviceId, 1))
        return id
    }

    private suspend fun lesson(): Pair<String, String> {
        val eventId = call("record_emotional_experience", appraisal(event())).getString("id")
        val reflection = call("save_emotional_reflection", JSONObject().put("id", "today").put("eventIds", JSONArray(listOf(eventId)))
            .put("helped", "Listening").put("misunderstood", "None observed").put("uncertain", "Other situations").put("adjustment", "Listen before advice")).getString("id")
        val lesson = call("save_support_lesson", JSONObject().put("id", "work").put("reflectionId", reflection).put("situation", "Work application stress")
            .put("approach", "Listen first").put("exceptions", "Give advice if explicitly requested").put("confidence", 0.5)).getString("id")
        return lesson to eventId
    }

    @Test fun lessonsRequireRelevantScopeAndLaterFeedbackAndForgetCascades() = runBlocking {
        val (lesson, eventId) = lesson()
        val reflectionId = JSONObject(repo.getCompanionRecord(lesson)!!.content).getJSONArray("relatedIds").getString(0)
        val second = JSONObject().put("id", "other").put("reflectionId", reflectionId).put("situation", "work application stress ")
            .put("approach", "Ask what they need").put("exceptions", "None").put("confidence", 0.5)
        assertTrue(tools.execute("save_support_lesson", second.toString()).payloadJson.contains("expectedVersion 1"))
        val experience = thread()
        val a = source("Please listen about my application stress").put("lessonId", lesson).put("experienceId", experience).put("outcome", "HELPFUL")
        assertFalse(tools.execute("record_support_application", a.toString()).ok)
        val application = call("record_support_application", a.put("outcome", "UNKNOWN")).getString("id")
        assertFalse(tools.execute("record_support_application", a.put("outcome", "HELPFUL").put("expectedVersion", 1).toString()).ok)
        clock.advance(1000)
        val feedback = source("Listening made the application stress easier").put("lessonId", lesson).put("experienceId", experience).put("outcome", "HELPFUL").put("expectedVersion", 1)
        call("record_support_application", feedback)
        call("record_support_application", feedback)
        val rows = repo.companionRecords()
        assertEquals(0.58, com.her.feelings.lessonConfidence(repo.getCompanionRecord(lesson)!!, rows), 0.0001)
        assertEquals(lesson, com.her.feelings.applicableLessons(rows, "work application stress", clock.nowMillis()).single().id)
        assertTrue(com.her.feelings.applicableLessons(rows, "pancake recipe", clock.nowMillis()).isEmpty())
        assertTrue(repo.forgetCompanion(eventId).containsAll(listOf(lesson, application)))
        assertNotNull(repo.getCompanionRecord(lesson)!!.deletedAt)
        assertNotNull(repo.getCompanionRecord(application)!!.deletedAt)
        assertTrue(com.her.feelings.applicableLessons(repo.companionRecords(), "work application stress", clock.nowMillis()).isEmpty())
    }

    @Test fun correctingEvidenceInvalidatesDerivedReflectionLessonAndApplication() = runBlocking {
        val (lesson, eventId) = lesson()
        val exp = thread()
        call("record_support_application", source().put("lessonId", lesson).put("experienceId", exp).put("outcome", "UNKNOWN"))
        val event = repo.getCompanionRecord(eventId)!!.data()
        event.remove("occurredAt"); event.remove("relatedIds")
        call("record_emotional_experience", event.put("expectedVersion", 1).put("interpretation", "Less certain after review"))
        val affected = repo.companionRecords().filter { it.kind in setOf("EMOTION_REFLECTION", "EMOTION_LESSON", "EMOTION_APPLICATION") }
        assertEquals(3, affected.size)
        assertTrue(affected.all { it.data().optBoolean("stale") })
    }
}
