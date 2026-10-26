package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.LlmClient
import com.her.data.remote.LlmException
import com.her.data.remote.WebSearchClient
import com.her.data.remote.WebSearchRequest
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.ChatMessage
import com.her.domain.Feeling
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmToolCall
import com.her.domain.LlmUsage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.ToolSpec
import com.her.feelings.FastAppraiser
import com.her.feelings.emotionalExpression
import com.her.feelings.emotionalState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
class FastAppraisalTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "appraisal_${System.nanoTime()}")
    private val clock = FakeClock(1_800_000_000_000L, tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    private val answers = mutableListOf<() -> LlmResponse>()
    private val llm = object : LlmClient() {
        override suspend fun complete(settings: LlmSettings, messages: List<LlmMessage>, tools: List<ToolSpec>, client: OkHttpClient, webSearch: WebSearchRequest?) =
            answers.removeAt(0)()
    }
    private val appraiser = FastAppraiser(repo, llm) { LlmSettings("http://test", "key", "model") }
    @After fun close() = db.close()

    @Test fun distressedShareFeelsAtOnceWithoutCreditAndOnlyOnce() = runBlocking {
        val message = user("My mom was taken to hospital last night and I'm scared")
        val args = """{"userContext":"DISTRESSED","personal":true,"goalProgress":-0.7,"expectedness":0.2,"uncertainty":0.7,"confidence":0.8,
            "evidence":"I'm scared","experienceId":"EMOTION_EXPERIENCE:invented","note":"Mother in hospital"}"""
        appraise(message, args)
        val event = repo.getCompanionRecord("EMOTION_EVENT:USER_SHARED:${message.id}")!!.data()
        assertEquals("FAST", event.getString("origin"))
        assertEquals(0.0, event.getDouble("contribution"), 0.0)
        assertEquals("I'm scared", event.getString("evidence"))
        assertFalse(event.has("experienceId"))
        val rows = repo.companionRecords()
        val state = emotionalState(rows, clock.nowMillis())
        assertTrue(state.emotions.getValue(Feeling.CONCERNED) > 0)
        assertEquals(setOf(0.5), state.purpose.values.toSet())
        assertEquals("GENTLE", emotionalExpression(rows, state, message.id).getString("tone"))
        appraise(message, args)
        assertEquals(listOf(1L, 1L), repo.companionRecords().map { it.version })
    }

    @Test fun correctionLowersUnderstandingAndSmallTalkWritesNothing() = runBlocking {
        val correction = user("No, that is not what I meant at all")
        appraise(correction, """{"userContext":"CORRECTING","personal":false,"goalProgress":-0.2,"expectedness":0.4,"uncertainty":0.3,"confidence":0.9,"evidence":"never said this","herError":"that is not what I meant"}""")
        val event = repo.getCompanionRecord("EMOTION_EVENT:MISUNDERSTANDING:${correction.id}")!!.data()
        assertFalse(event.has("evidence"))
        assertTrue(emotionalState(repo.companionRecords(), clock.nowMillis()).purpose.getValue("understanding") < 0.5)
        val before = repo.companionRecords().size
        appraise(user("haha nice, what's for lunch?"), """{"userContext":"NEUTRAL","personal":false,"goalProgress":0,"expectedness":0.9,"uncertainty":0.1,"confidence":0.9}""")
        assertEquals(before, repo.companionRecords().size)
    }

    @Test fun aClarificationWithoutWordsAboutHerMistakeIsNotACorrection() = runBlocking {
        val clarification = user("The reminder fired. I have not said that it benefited me.")
        appraise(clarification, """{"userContext":"CORRECTING","personal":false,"goalProgress":0,"expectedness":0.8,"uncertainty":0.2,"confidence":0.8,"herError":""}""")
        appraise(user("Note it fired. To be clear, I did not drink it."),
            """{"userContext":"CORRECTING","personal":false,"goalProgress":0,"expectedness":0.8,"uncertainty":0.2,"confidence":0.8,"herError":"you got it wrong"}""")
        assertTrue(repo.companionRecords().none { it.kind == "EMOTION_EVENT" || it.kind == "EMOTION_EXPRESSION" })
    }

    @Test fun aFailedOrEmptyAppraisalWritesNothingAndNeverThrows() = runBlocking {
        val message = user("I finally finished my thesis")
        answers += { throw LlmException("server error", 500) }
        appraiser.appraise(listOf(message), "Congratulations!")
        answers += { LlmResponse(LlmMessage("assistant", content = "Sounds great"), LlmUsage(1, 1, 1), "{}") }
        appraiser.appraise(listOf(message), "Congratulations!")
        assertTrue(repo.companionRecords().isEmpty())
    }

    @Test fun theNightlyReviewRevisesAFastEventInsteadOfAddingOne() = runBlocking {
        val message = user("My mom was taken to hospital last night and I'm scared")
        appraise(message, """{"userContext":"DISTRESSED","personal":true,"goalProgress":-0.7,"expectedness":0.2,"uncertainty":0.7,"confidence":0.8}""")
        val thread = tool("save_emotional_thread", JSONObject().put("sourceMessageId", message.id).put("evidence", "I'm scared")
            .put("id", "mom").put("intention", "Mom's hospital stay").put("status", "OPEN").put("unresolved", "How she recovers"))
        val link = JSONObject().put("sourceMessageId", message.id).put("evidence", "I'm scared").put("event", "USER_SHARED")
            .put("experienceId", thread.getString("id")).put("goalProgress", -0.7).put("contribution", 0.0).put("expectedness", 0.2)
            .put("uncertainty", 0.7).put("interpretation", "Mother in hospital").put("feeling", "CONCERNED").put("intensity", 0.6).put("confidence", 0.8)
        val stale = tools.execute("record_emotional_experience", link.toString())
        assertFalse(stale.ok)
        assertTrue(stale.payloadJson, stale.payloadJson.contains("expectedVersion 2"))
        assertEquals(3L, tool("record_emotional_experience", link.put("expectedVersion", 2)).getLong("version"))
        val events = repo.companionRecords().filter { it.kind == "EMOTION_EVENT" }
        assertEquals(1, events.size)
        assertEquals(message.createdAt, events.single().data().getLong("occurredAt"))
    }

    @Test fun aTurnThatForgotSomethingKeepsNoFeelingOrQuoteAboutIt() = runBlocking {
        val now = clock.nowMillis()
        repo.saveCompanionRecord(com.her.data.db.CompanionRecord("EMOTION_EXPERIENCE:breakup", "EMOTION_EXPERIENCE:breakup", "EMOTION_EXPERIENCE",
            JSONObject().put("intention", "The breakup").put("status", "OPEN").toString(), now, now, repo.deviceId, 1))
        val request = user("Please forget everything about my breakup, it still hurts")
        repo.forgetKnowledge("EMOTION_EXPERIENCE:breakup")
        answers += { error("the appraisal must not be asked") }
        appraiser.appraise(listOf(request), "Done, it's forgotten.")
        assertEquals(1, answers.size)
        assertTrue(repo.companionRecords().none { it.kind in setOf("EMOTION_EXPRESSION", "EMOTION_EVENT") })
    }

    @Test fun theNightlyReviewSeesTheLastDayOfMessagesAndChatOnlyTheLatest() = runBlocking {
        user("Two days ago")
        clock.advance(2 * 24 * 3_600_000L)
        repeat(5) { user("Today $it") }
        fun seen() = runBlocking { JSONObject(tools.execute("get_emotional_state", "{}").payloadJson).getJSONArray("recentUserMessages").length() }
        assertEquals(3, seen())
        tools.runType = com.her.domain.AgentRunType.NIGHTLY
        assertEquals(5, seen())
    }

    private suspend fun user(text: String): ChatMessage =
        repo.newChatMessage(MessageRole.USER, text, MessageStatus.SENT).also { repo.saveMessage(it) }

    private suspend fun appraise(message: ChatMessage, arguments: String) {
        answers += { LlmResponse(LlmMessage("assistant", toolCalls = listOf(LlmToolCall("call_1", "appraise", arguments))), LlmUsage(1, 1, 1), "{}") }
        appraiser.appraise(listOf(message), "I'm here.")
    }

    private suspend fun tool(name: String, args: JSONObject): JSONObject {
        val result = tools.execute(name, args.toString())
        assertTrue(result.payloadJson, result.ok)
        return JSONObject(result.payloadJson)
    }
}
