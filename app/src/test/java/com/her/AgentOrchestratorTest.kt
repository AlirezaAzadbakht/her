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
import com.her.domain.MessageStatus
import com.her.domain.ToolSpec
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import java.time.Instant
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        tools = ToolRegistry(repo, ranker, settings, calendar, WebSearchClient(), onUserMessage = { text ->
            repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, text, MessageStatus.SENT, """{"proactive":true}"""))
        })
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
    fun retriesTruncatedStreamsButNotConfigErrors() {
        assertTrue(AgentOrchestrator.isRetryable(LlmException("LLM stream truncated")))
        assertTrue(AgentOrchestrator.isRetryable(LlmException("LLM response had no choices")))
        assertFalse(AgentOrchestrator.isRetryable(LlmException("LLM is not configured. Add a base URL, API key, and model in Settings.")))
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
    fun learningNudgeReachesNotificationAndChatOnce() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        assertTrue(tools.execute("save_learning_path", """{"id":"python","title":"Python","goal":"Automation","remindersEnabled":true,"reminderTime":"09:00"}""").ok)
        val llm = ScriptedLlm(
            { LlmResponse(LlmMessage(role = "assistant", content = null, toolCalls = listOf(
                LlmToolCall("study", "send_learning_reminder", """{"pathId":"path:python","content":"Ready for Python?"}"""))), LlmUsage(10, 5, 1), "{}") },
            { reply("NO_NOTIFICATION") },
        )
        orchestrator(llm).runHourly()
        assertEquals(listOf("Ready for Python?"), shown)
        assertEquals("Ready for Python?", repo.recentMessages(10).last().content)
    }

    @Test
    fun sendUserMessageHourlyNudgeIsNotRepeated() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        clock.set(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli())
        val orchestrator = orchestrator(
            ScriptedLlm(
                { toolCall("send_user_message", """{"content":"The passport renewal is overdue."}""") },
                { reply("NO_NOTIFICATION") },
                { toolCall("send_user_message", """{"content":"The passport renewal is overdue."}""") },
                { reply("NO_NOTIFICATION") },
            ),
        )
        orchestrator.runHourly()
        clock.advance(2 * 60 * 60 * 1000)
        orchestrator.runHourly()

        assertEquals(listOf("The passport renewal is overdue."), shown)
    }

    @Test
    fun hourlyDoesNotRepeatARecentProactiveNudge() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        clock.set(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli())
        val orchestrator = orchestrator(
            ScriptedLlm(
                { reply("The passport renewal is overdue.") },
                { reply("The passport renewal is overdue.") },
            ),
        )
        orchestrator.runHourly()
        clock.advance(2 * 60 * 60 * 1000)
        orchestrator.runHourly()

        assertEquals(listOf("The passport renewal is overdue."), shown)
        assertEquals(1, repo.recentMessages(10).count { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun hourlyStillDeliversAnUnrelatedNudge() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        clock.set(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli())
        val orchestrator = orchestrator(
            ScriptedLlm(
                { reply("The passport renewal is overdue.") },
                { reply("Milk is on the grocery list.") },
            ),
        )
        orchestrator.runHourly()
        clock.advance(2 * 60 * 60 * 1000)
        orchestrator.runHourly()

        assertEquals(
            listOf("The passport renewal is overdue.", "Milk is on the grocery list."),
            shown,
        )
    }

    @Test
    fun recentProactiveNudgeDoesNotSuppressBriefing() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        clock.set(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli())
        repo.saveMessage(
            repo.newChatMessage(
                MessageRole.ASSISTANT,
                "The passport renewal is overdue.",
                MessageStatus.SENT,
                """{"proactive":true}""",
            ),
        )
        orchestrator(ScriptedLlm({ reply("The passport renewal is overdue.") })).runBriefing()

        assertEquals(listOf("The passport renewal is overdue."), shown)
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
    fun sendUserMessageHeldByQuietHoursStillReachesTheMorning() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        clock.set(Instant.parse("2026-09-12T01:00:00Z").toEpochMilli())
        val orchestrator = orchestrator(
            ScriptedLlm(
                { toolCall("send_user_message", """{"content":"The passport renewal is overdue."}""") },
                { reply("NO_NOTIFICATION") },
                { toolCall("send_user_message", """{"content":"The passport renewal is overdue."}""") },
                { reply("NO_NOTIFICATION") },
            ),
        )
        orchestrator.runHourly()
        assertTrue(repo.recentMessages(10).isEmpty())
        clock.set(Instant.parse("2026-09-12T09:00:00Z").toEpochMilli())
        orchestrator.runHourly()

        assertEquals(listOf("The passport renewal is overdue."), shown)
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

    @Test
    fun oneCallBudgetWithholdsTools() = runBlocking {
        settings.update { it.copy(chatToolCallLimit = 1) }
        val llm = ScriptedLlm({ reply("Hi.") })
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("hi")
        val result = orchestrator.processOutbox()!!
        assertEquals("Hi.", result.assistantText)
        assertTrue(llm.toolsSeen.single().isEmpty())
    }

    @Test
    fun retriesMalformedJsonThenSucceeds() = runBlocking {
        val llm = ScriptedLlm(
            { throw LlmException("malformed LLM JSON: not json") },
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
    fun failedTurnDoesNotDuplicateWritesOnRetry() = runBlocking {
        val llm = ScriptedLlm(
            { toolCall("create_task", """{"title":"Buy milk"}""") },
            { throw LlmException("server error", 500) },
            { throw LlmException("server error", 500) },
            { throw LlmException("server error", 500) },
            { toolCall("create_task", """{"title":"Buy milk"}""") },
            { reply("Added it.") },
        )
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("Buy milk")
        val first = orchestrator.processOutbox()!!
        assertTrue(first.failed)
        assertEquals(1, repo.tasks().size)
        val second = orchestrator.processOutbox()!!
        assertFalse(second.failed)
        assertEquals(1, repo.tasks().size)
    }

    @Test
    fun autonomousRunsCannotForgetMemory() = runBlocking {
        tools.execute("remember", """{"content":"Likes tea","scope":"long_term"}""")
        val llm = ScriptedLlm(
            { toolCall("forget_memory", """{"everything":true}""") },
            { reply("NO_NOTIFICATION") },
        )
        orchestrator(llm).runHourly()
        assertTrue(llm.toolsSeen[0].none { it.name == "forget_memory" })
        assertEquals(1, repo.allLong().count { it.deletedAt == null })
    }

    @Test
    fun duplicateToolCallsAreNotExecutedTwice() = runBlocking {
        val llm = ScriptedLlm(
            {
                LlmResponse(
                    LlmMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            LlmToolCall("a", "create_task", """{"title":"Call Sara"}"""),
                            LlmToolCall("b", "create_task", """{"title":"Call Sara"}"""),
                        ),
                    ),
                    LlmUsage(10, 5, 1),
                    "{}",
                )
            },
            { reply("Noted.") },
        )
        orchestrator(llm).run {
            enqueueUserMessage("Remind me to call Sara")
            processOutbox()
        }
        assertEquals(1, repo.tasks().size)
    }

    @Test
    fun elidesOldToolResultsPastTheBudget() {
        val messages = MutableList(3) { i ->
            LlmMessage(role = "tool", content = "x".repeat(40_000), toolCallId = "c$i", name = "search_memory")
        }
        AgentOrchestrator.elideToolResults(messages, maxChars = 50_000)
        assertTrue(messages.any { it.content == """{"ok":true,"elided":true}""" })
        assertTrue(messages.sumOf { it.content?.length ?: 0 } <= 50_000 + 40_000)
    }

    @Test
    fun runsAreSerialized() = runBlocking {
        val started = mutableListOf<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val llm = object : LlmClient() {
            override suspend fun complete(
                settings: LlmSettings,
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                client: OkHttpClient,
                webSearch: WebSearchRequest?,
            ): LlmResponse {
                started += "hourly"
                gate.await()
                return reply("NO_NOTIFICATION")
            }

            override suspend fun stream(
                settings: LlmSettings,
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                onDelta: (String) -> Unit,
            ): LlmResponse {
                started += "chat"
                return reply("ok")
            }
        }
        val orch = orchestrator(llm)
        coroutineScope {
            val hourly = launch { orch.runHourly() }
            while (started.isEmpty()) yield()
            val chat = launch {
                orch.enqueueUserMessage("hi")
                orch.processOutbox()
            }
            delay(40)
            assertEquals(listOf("hourly"), started)
            gate.complete(Unit)
            hourly.join()
            chat.join()
        }
        assertEquals(listOf("hourly", "chat"), started)
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

    private fun toolCall(name: String, arguments: String = "{}") = LlmResponse(
        LlmMessage(role = "assistant", toolCalls = listOf(LlmToolCall("call_1", name, arguments))),
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
