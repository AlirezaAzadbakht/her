package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.prompt.Identity
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.runner.Receipts
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchClient
import com.her.data.remote.WebSearchRequest
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmToolCall
import com.her.domain.LlmUsage
import com.her.domain.MessageRole
import com.her.domain.ToolSpec
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class OrchestratorReceiptsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "orchestrator_receipts_${System.nanoTime()}")
    private val repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()))
    private val ranker = HybridRanker(repo)
    private val calendar = CalendarDataSource(context)
    private val tools = ToolRegistry(repo, ranker, settings, calendar, WebSearchClient())
    private val contextBuilder = ContextBuilder(repo, ranker, settings, calendar)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun receiptsLandOnTheSavedReply() = runBlocking {
        val llm = ScriptedLlm(
            toolCalls(
                "add_grocery" to """{"name":"rice"}""",
                "update_agent_state" to """{"kind":"note","content":"They cook a lot on weekends"}""",
            ),
            reply("Added rice to the list."),
        )
        orchestrator(llm).run {
            enqueueUserMessage("We're out of rice.")
            processOutbox()
        }

        val saved = repo.recentMessages(5).last { it.role == MessageRole.ASSISTANT }
        val receipts = Receipts.fromMetadata(saved.metadataJson)
        assertEquals("private notes are not announced", listOf("added rice to groceries"), receipts.map { it.label })
        assertTrue(receipts.single().undoable)
    }

    @Test
    fun readsLeaveNoReceipts() = runBlocking {
        val llm = ScriptedLlm(toolCalls("get_current_time" to "{}"), reply("It's ten in the morning."))
        orchestrator(llm).run {
            enqueueUserMessage("What time is it?")
            processOutbox()
        }

        assertNull(repo.recentMessages(5).last { it.role == MessageRole.ASSISTANT }.metadataJson)
    }

    @Test
    fun digestIsOneRowAndAlwaysInContext() = runBlocking {
        repeat(12) { tools.execute("update_agent_state", """{"kind":"note","content":"note $it"}""") }
        assertTrue(tools.execute("update_agent_state", """{"kind":"digest","content":"They chose the Park Street school."}""").ok)
        assertTrue(tools.execute("update_agent_state", """{"kind":"digest","content":"Lily starts at Park Street in October."}""").ok)

        assertEquals(1, repo.agentState().count { it.kind == Identity.DIGEST_KIND })
        val bundle = contextBuilder.build().systemBundle
        assertTrue(bundle.contains("Recent days (your notes):"))
        assertTrue(bundle.contains("Lily starts at Park Street in October."))
        assertFalse(bundle.contains("They chose the Park Street school."))
    }

    private fun orchestrator(llm: LlmClient) = AgentOrchestrator(
        repo = repo,
        llm = llm,
        llmSettings = { LlmSettings(baseUrl = "http://test", apiKey = "key", model = "model") },
        settings = settings,
        contextBuilder = contextBuilder,
        tools = tools,
        notifier = object : Notifier(context) {
            override fun show(text: String, briefing: Boolean) = Unit
        },
        policy = NotificationPolicy(settings),
        retryDelayMs = { 0L },
    )

    private fun reply(text: String) =
        LlmResponse(LlmMessage(role = "assistant", content = text), LlmUsage(10, 5, 1), "{}")

    private fun toolCalls(vararg calls: Pair<String, String>) = LlmResponse(
        LlmMessage(
            role = "assistant",
            toolCalls = calls.mapIndexed { i, (name, arguments) -> LlmToolCall("call_$i", name, arguments) },
        ),
        LlmUsage(10, 5, 1),
        "{}",
    )

    private class ScriptedLlm(vararg responses: LlmResponse) : LlmClient() {
        private val script = responses.toMutableList()

        override suspend fun complete(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            client: OkHttpClient,
            webSearch: WebSearchRequest?,
        ): LlmResponse = next()

        override suspend fun stream(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            onDelta: (String) -> Unit,
        ): LlmResponse = next()

        private fun next(): LlmResponse {
            check(script.isNotEmpty()) { "scripted LLM ran out of responses" }
            return script.removeAt(0)
        }
    }
}
