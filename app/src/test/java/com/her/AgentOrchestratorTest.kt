package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.core.QuietHours
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AgentOrchestratorTest {
    private lateinit var db: HerDatabase
    private lateinit var settings: AppSettingsStore
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry
    private lateinit var contextBuilder: ContextBuilder
    private lateinit var notifier: Notifier
    private val shown = mutableListOf<String>()

    // 22:30 UTC on Sep 12 is already 02:00 on Sep 13 in Tehran.
    private val clock = FakeClock(Instant.parse("2026-09-12T22:30:00Z").toEpochMilli())

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = AppSettingsStore(context, "orchestrator_test_${System.nanoTime()}")
        repo = HerRepository(db, settings, clock)
        val ranker = HybridRanker(repo)
        val calendar = CalendarDataSource(context)
        tools = ToolRegistry(repo, ranker, settings, calendar, WebSearchClient())
        contextBuilder = ContextBuilder(repo, ranker, settings, calendar)
        notifier = object : Notifier(context) {
            override fun show(text: String, briefing: Boolean) {
                shown += text
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun finalCallWithholdsToolsSoTheTurnEndsWithAReply() = runBlocking {
        settings.update { it.copy(chatToolCallLimit = 2) }
        val llm = ScriptedLlm(
            { toolCall("get_current_time") },
            { reply("It's two in the morning.") },
        )
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("What time is it?")
        val result = orchestrator.processOutbox()!!

        assertEquals("It's two in the morning.", result.assistantText)
        assertTrue(llm.toolsSeen[0].isNotEmpty())
        assertTrue("the last call must not offer tools", llm.toolsSeen[1].isEmpty())
    }

    @Test
    fun retriesRateLimitsThenSucceeds() = runBlocking {
        val llm = ScriptedLlm(
            { throw LlmException("slow down", 429) },
            { reply("ok") },
        )
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("hi")
        val result = orchestrator.processOutbox()!!

        assertFalse(result.failed)
        assertEquals("ok", result.assistantText)
        assertEquals(2, llm.calls)
    }

    @Test
    fun doesNotRetryBadRequests() = runBlocking {
        val llm = ScriptedLlm({ throw LlmException("bad request", 400) })
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("hi")
        val result = orchestrator.processOutbox()!!

        assertTrue(result.failed)
        assertEquals(1, llm.calls)
    }

    @Test
    fun nightlyStampsTheProfileDayNotTheDeviceDay() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "Asia/Tehran"))
        val orchestrator = orchestrator(ScriptedLlm({ reply("done") }))
        orchestrator.runNightly()

        assertEquals("2026-09-13", settings.read().lastNightlyDate)
    }

    @Test
    fun hourlySilenceNeitherPersistsNorNotifies() = runBlocking {
        val orchestrator = orchestrator(ScriptedLlm({ reply(" no_notification ") }))
        val result = orchestrator.runHourly()

        assertNull(result.assistantText)
        assertTrue(shown.isEmpty())
        assertTrue(repo.recentMessages(10).isEmpty())
    }

    @Test
    fun hourlyNudgeIsSavedSoTheScreenMatchesTheNotification() = runBlocking {
        // 22:30 UTC is outside the default quiet hours; the device zone might not be.
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val orchestrator = orchestrator(ScriptedLlm({ reply("The passport renewal is overdue.") }))
        orchestrator.runHourly()

        assertEquals(listOf("The passport renewal is overdue."), shown)
        val latest = repo.recentMessages(10).last { it.role == MessageRole.ASSISTANT }
        assertEquals("The passport renewal is overdue.", latest.content)
    }

    @Test
    fun hourlyNudgeSuppressedByQuietHoursIsNotSaved() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        settings.update { it.copy(quietHours = QuietHours(22 * 60, 23 * 60)) }
        val orchestrator = orchestrator(ScriptedLlm({ reply("The passport renewal is overdue.") }))
        orchestrator.runHourly()

        assertTrue(shown.isEmpty())
        assertTrue(repo.recentMessages(10).isEmpty())
    }

    @Test
    fun largeToolPayloadsAreTruncatedForTheModel() {
        val payload = "x".repeat(AgentOrchestrator.MAX_TOOL_PAYLOAD_CHARS + 1000)
        val trimmed = AgentOrchestrator.forModel(payload)

        assertTrue(trimmed.length < payload.length)
        assertTrue(trimmed.endsWith("[truncated 1000 chars]"))
        assertEquals("short", AgentOrchestrator.forModel("short"))
    }

    @Test
    fun malformedToolArgumentsExplainThemselves() = runBlocking {
        val result = tools.execute("get_current_time", "{not json")

        assertFalse(result.ok)
        assertTrue(JSONObject(result.payloadJson).getString("error").contains("valid JSON"))
    }

    private fun orchestrator(llm: LlmClient) = AgentOrchestrator(
        repo = repo,
        llm = llm,
        llmSettings = { LlmSettings(baseUrl = "http://test", apiKey = "key", model = "model") },
        settings = settings,
        contextBuilder = contextBuilder,
        tools = tools,
        notifier = notifier,
        policy = NotificationPolicy(settings),
        retryDelayMs = { 0L },
    )

    private fun reply(text: String) =
        LlmResponse(LlmMessage(role = "assistant", content = text), LlmUsage(10, 5, 1), "{}")

    private fun toolCall(name: String) = LlmResponse(
        LlmMessage(role = "assistant", toolCalls = listOf(LlmToolCall("call_1", name, "{}"))),
        LlmUsage(10, 5, 1),
        "{}",
    )

    private class ScriptedLlm(vararg steps: () -> LlmResponse) : LlmClient() {
        private val script = steps.toMutableList()
        val toolsSeen = mutableListOf<List<ToolSpec>>()
        val calls: Int get() = toolsSeen.size

        override suspend fun complete(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            client: OkHttpClient,
            webSearch: WebSearchRequest?,
        ): LlmResponse = next(tools)

        override suspend fun stream(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            onDelta: (String) -> Unit,
        ): LlmResponse = next(tools)

        private fun next(tools: List<ToolSpec>): LlmResponse {
            toolsSeen += tools
            check(script.isNotEmpty()) { "scripted LLM ran out of responses" }
            return script.removeAt(0)()
        }
    }
}
