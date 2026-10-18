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
import com.her.domain.Feeling
import com.her.feelings.emotionalState
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

}
