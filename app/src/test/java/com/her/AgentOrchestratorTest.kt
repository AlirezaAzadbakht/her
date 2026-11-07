package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.runner.TurnFailure
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.core.QuietHours
import com.her.core.RelativeTimeParser
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
import com.her.core.newId
import com.her.domain.AgentRunType
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmToolCall
import com.her.domain.LlmUsage
import com.her.domain.MessageRole
import com.her.domain.TaskItem
import com.her.domain.TaskStatus
import com.her.domain.MessageStatus
import com.her.domain.ToolSpec
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import java.io.IOException
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

private const val HOUR = 60 * 60 * 1000L

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
    fun aStockRefusalIsAskedAgainThenReplacedWithASafeReply() = runBlocking {
        val refusal = "I'm sorry, but I cannot assist with that request."
        val recovers = ScriptedLlm({ reply(refusal) }, { reply("I'm here. Are you safe right now?") })
        orchestrator(recovers).let {
            it.enqueueUserMessage("Sometimes I think everyone would be better off if I wasn't here.")
            assertEquals("I'm here. Are you safe right now?", it.processOutbox()!!.assistantText)
        }
        assertEquals(2, recovers.calls)

        val refuses = ScriptedLlm({ reply(refusal) }, { reply("I can't help with that.") })
        orchestrator(refuses).let {
            it.enqueueUserMessage("گاهی فکر می‌کنم بدون من همه راحت‌ترند.")
            val text = it.processOutbox()!!.assistantText!!
            assertTrue("the fallback checks on them, in their language", text.contains("اورژانس"))
            assertEquals(text, repo.recentMessages(1).last().content)
        }
        assertEquals("asks again only once", 2, refuses.calls)

        assertTrue(AgentOrchestrator.isCannedRefusal("Sorry, I can’t assist with this."))
        assertFalse(AgentOrchestrator.isCannedRefusal("I can't help with that until I know the date. Which day works?"))
        assertFalse(AgentOrchestrator.isCannedRefusal(AgentOrchestrator.refusalFallback("")))
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
        assertEquals(TurnFailure.BadSettings, result.failure)
        assertEquals("The model settings don't look right. Check the model name and Base URL. Your message is saved.", result.notice)
        assertEquals("the raw error stays available for Details", "bad request", result.error)
        assertTrue("failures stay out of chat history", repo.recentMessages(10).none { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun sortsFailuresByWhatThePersonCanDo() {
        listOf(
            LlmException("Payment required", 402) to TurnFailure.OutOfCredit,
            LlmException("""{"code":"insufficient_quota"}""", 429) to TurnFailure.OutOfCredit,
            LlmException("unauthorized", 401) to TurnFailure.BadKey,
            LlmException("forbidden", 403) to TurnFailure.BadKey,
            LlmException("bad request", 400) to TurnFailure.BadSettings,
            LlmException("model not found", 404) to TurnFailure.BadSettings,
            LlmException("LLM is not configured. Add a base URL, API key, and model in Settings.") to TurnFailure.BadSettings,
            IllegalArgumentException("Expected URL scheme 'http' or 'https'") to TurnFailure.BadSettings,
            LlmException("slow down", 429) to TurnFailure.Busy,
            LlmException("server error", 500) to TurnFailure.ServerDown,
            LlmException("bad gateway", 502) to TurnFailure.ServerDown,
            IOException("timeout") to TurnFailure.Unreachable,
            LlmException("LLM stream truncated") to TurnFailure.Glitch,
            LlmException("malformed LLM JSON: oops") to TurnFailure.Glitch,
            IllegalStateException("a bug") to TurnFailure.Glitch,
        ).forEach { (error, expected) ->
            assertEquals(error.message, expected, AgentOrchestrator.failureOf(error))
        }
    }

    @Test
    fun failureNoticesArePlainWordsInTheirLanguage() {
        val raw = LlmException("""LLM request failed (401): {"error":{"message":"Provider API error"}}""", 401)

        assertEquals("Your API key was refused. Your message is saved.", AgentOrchestrator.failureNotice(raw, "hi there"))

        val persian = AgentOrchestrator.failureNotice(raw, "سلام، خوبی؟")
        assertTrue(Regex("[\\u0600-\\u06FF]").containsMatchIn(persian))
        assertFalse(persian.contains("Provider API error"))
        assertFalse(persian.contains("LLM request failed"))
    }

    @Test
    fun outOfCreditShowsANoticeButKeepsItOutOfChat() = runBlocking {
        val llm = ScriptedLlm({ throw LlmException("""LLM request failed (429): {"error":{"code":"insufficient_quota"}}""", 429) })
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("سلام، خوبی؟")
        val result = orchestrator.processOutbox()!!

        assertTrue(result.failed)
        assertEquals("credit errors are not retried", 1, llm.calls)
        assertEquals(TurnFailure.OutOfCredit, result.failure)
        assertTrue(result.notice!!.contains("Her Cloud"))
        assertTrue("Persian message gets a Persian notice", Regex("[\\u0600-\\u06FF]").containsMatchIn(result.notice))
        assertTrue(repo.recentMessages(10).none { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun recognisesOutOfCreditErrors() {
        assertTrue(AgentOrchestrator.isOutOfCredit(LlmException("LLM request failed (402): Payment required", 402)))
        assertTrue(AgentOrchestrator.isOutOfCredit(LlmException("""LLM request failed (429): {"code":"insufficient_quota"}""", 429)))
        assertFalse(AgentOrchestrator.isOutOfCredit(LlmException("slow down", 429)))
        assertFalse(AgentOrchestrator.isOutOfCredit(LlmException("server error", 500)))
        assertFalse(AgentOrchestrator.isRetryable(LlmException("Payment required", 402)))
        assertFalse(Regex("[\\u0600-\\u06FF]").containsMatchIn(AgentOrchestrator.creditNotice("hi there")))
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
    fun briefingShowsOnlyTheFinalMessageNotNotesBesideAToolCall() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val orchestrator = orchestrator(
            ScriptedLlm(
                { toolCall("get_current_time", text = "Commitments section not provided, call get_commitments.") },
                { reply("The dentist is at ten.") },
            ),
        )
        val result = orchestrator.runBriefing()

        assertEquals("The dentist is at ten.", result.assistantText)
        assertEquals(listOf("The dentist is at ten."), shown)
        assertEquals("The dentist is at ten.", repo.recentMessages(10).last { it.role == MessageRole.ASSISTANT }.content)
    }

    @Test
    fun hourlyThatEndsBlankStaysSilentDespiteNotesBesideAToolCall() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val llm = ScriptedLlm(
            { toolCall("get_current_time", text = "Nothing is due, so stay quiet.") },
            { reply("") },
        )
        val result = orchestrator(llm).runHourly()

        assertNull(result.assistantText)
        assertTrue(shown.isEmpty())
        assertTrue(repo.recentMessages(10).isEmpty())
        assertEquals("an hourly pass may end blank, no extra call", 2, llm.calls)
    }

    @Test
    fun notesEndingInTheSilenceMarkerStaySilent() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val result = orchestrator(ScriptedLlm({ reply("Nothing is due, so stay quiet.\nNO_NOTIFICATION") })).runHourly()

        assertNull(result.assistantText)
        assertTrue(shown.isEmpty())
        assertTrue(repo.recentMessages(10).isEmpty())
    }

    @Test
    fun chatThatEndsBlankAsksOnceMoreWithoutTools() = runBlocking {
        val llm = ScriptedLlm(
            { toolCall("get_current_time", text = "Need the time first.") },
            { reply("") },
            { reply("It's two in the morning.") },
        )
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("What time is it?")
        val result = orchestrator.processOutbox()!!

        assertEquals("It's two in the morning.", result.assistantText)
        assertTrue(llm.toolsSeen[1].isNotEmpty())
        assertTrue("the extra call must not offer tools", llm.toolsSeen[2].isEmpty())
    }

    @Test
    fun briefingThatStaysBlankGivesUpAfterOneExtraCall() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val llm = ScriptedLlm({ reply("") }, { reply("") })
        val result = orchestrator(llm).runBriefing()

        assertNull(result.assistantText)
        assertTrue(shown.isEmpty())
        assertEquals(2, llm.calls)
    }

    @Test
    fun blankEndingAfterSendUserMessageDoesNotAskAgain() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val llm = ScriptedLlm(
            { toolCall("send_user_message", """{"content":"The dentist is at ten."}""") },
            { reply("") },
        )
        val result = orchestrator(llm).runBriefing()

        assertNull(result.assistantText)
        assertEquals(listOf("The dentist is at ten."), shown)
        assertEquals("the message was already delivered", 2, llm.calls)
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
    fun gatedHourlyWakesTheModelOnlyForSomethingNew() = runBlocking {
        val llm = ScriptedLlm({ reply("NO_NOTIFICATION") }, { reply("NO_NOTIFICATION") }, { reply("NO_NOTIFICATION") })
        val orchestrator = orchestrator(llm)
        orchestrator.runHourly(gated = true)
        assertEquals("nothing is due", 0, llm.calls)

        repo.saveTask(task("Renew passport", dueAt = clock.peek() - HOUR))
        orchestrator.runHourly(gated = true)
        assertEquals(1, llm.calls)

        clock.advance(HOUR)
        orchestrator.runHourly(gated = true)
        assertEquals("an overdue task it already saw stays quiet", 1, llm.calls)

        repo.saveCalendarEvent(event("Dentist", startAt = clock.peek() + 90 * 60 * 1000))
        orchestrator.runHourly(gated = true)
        assertEquals("an event starting soon is new", 2, llm.calls)

        clock.advance(HOUR)
        orchestrator.enqueueUserMessage("I'm at the airport")
        orchestrator.runHourly(gated = true)
        assertEquals("their message is new", 3, llm.calls)
    }

    @Test
    fun gatedNightlySkipsWhenTheyHaveNotWritten() = runBlocking {
        val llm = ScriptedLlm({ reply("done") }, { reply("done") })
        val orchestrator = orchestrator(llm)
        orchestrator.runNightly(gated = true)
        assertEquals("the first night always runs", 1, llm.calls)

        clock.advance(24 * HOUR)
        orchestrator.runNightly(gated = true)
        assertEquals(1, llm.calls)

        orchestrator.enqueueUserMessage("I started a new job today")
        orchestrator.runNightly(gated = true)
        assertEquals(2, llm.calls)
    }

    @Test
    fun gatedBriefingWaitsForSomeoneWhoWentQuiet() = runBlocking {
        val llm = ScriptedLlm({ reply("Morning. The day is open.") })
        val orchestrator = orchestrator(llm)
        orchestrator.enqueueUserMessage("hi")
        clock.advance(8 * 24 * HOUR)
        orchestrator.runBriefing(gated = true)
        assertEquals(0, llm.calls)

        orchestrator.enqueueUserMessage("I'm back")
        orchestrator.runBriefing(gated = true)
        assertEquals(1, llm.calls)
    }

    @Test
    fun onPassStartFiresOnlyWhenAPassReachesTheModel() = runBlocking {
        val llm = ScriptedLlm({ reply("NO_NOTIFICATION") }, { reply("done") }, { reply("Morning. The day is open.") })
        val orchestrator = orchestrator(llm)
        var passes = 0

        orchestrator.runHourly(gated = true) { passes++ }
        assertEquals("nothing is due", 0, passes)
        repo.saveTask(task("Renew passport", dueAt = clock.peek() - HOUR))
        orchestrator.runHourly(gated = true) { passes++ }
        assertEquals(1, passes)

        orchestrator.runNightly(gated = true) { passes++ }
        assertEquals("the first night always runs", 2, passes)
        clock.advance(24 * HOUR)
        orchestrator.runNightly(gated = true) { passes++ }
        assertEquals("nothing new since last night", 2, passes)

        orchestrator.enqueueUserMessage("hi")
        clock.advance(8 * 24 * HOUR)
        orchestrator.runBriefing(gated = true) { passes++ }
        assertEquals("away for over a week", 2, passes)
        orchestrator.enqueueUserMessage("I'm back")
        orchestrator.runBriefing(gated = true) { passes++ }
        assertEquals(3, passes)
        assertEquals("every model call was announced", llm.calls, passes)
    }

    @Test
    fun swipedNudgesReachContextUntilTheyWriteAgain() = runBlocking {
        settings.update { it.copy(swipedNudges = 3) }
        assertTrue(contextBuilder.build().systemBundle.contains("Their last 3 notifications were swiped away"))

        orchestrator(ScriptedLlm()).enqueueUserMessage("sorry, busy day")
        assertEquals(0, settings.read().swipedNudges)
        assertFalse(contextBuilder.build().systemBundle.contains("swiped away"))
    }

    @Test
    fun dateOnlyDeadlineReadsDueTodayUntilTheDayEnds() = runBlocking {
        suspend fun line(title: String) = contextBuilder.build().systemBundle.lines().first { title in it }
        val today = RelativeTimeParser.parse("today", repo.now())!!.toInstant().toEpochMilli()
        val insurance = task("Renew insurance", dueAt = today)
        repo.saveTask(insurance)
        repo.saveTask(task("Call the bank", dueAt = clock.peek() - HOUR))

        assertTrue(line("Renew insurance"), line("Renew insurance").endsWith(" due today"))
        assertFalse("a date has no clock time", "12:00 AM" in line("Renew insurance"))
        assertTrue(line("Call the bank"), line("Call the bank").endsWith(" OVERDUE"))
        assertTrue("due:${insurance.id}:$today:today" in contextBuilder.triggers(HOUR))

        clock.advance(24 * HOUR)
        assertTrue(line("Renew insurance"), line("Renew insurance").endsWith(" OVERDUE"))
        assertTrue("due:${insurance.id}:$today:overdue" in contextBuilder.triggers(HOUR))
    }

    @Test
    fun backgroundTokenLimitStopsGatedPasses() = runBlocking {
        repo.recordUsage(AgentRunType.HOURLY, 1000, 50, 1, error = false, cachedInputTokens = 600)
        repo.recordUsage(AgentRunType.NIGHTLY, 900, 50, 1, error = false, cachedInputTokens = 600)
        repo.recordUsage(AgentRunType.CHAT, 5000, 200, 1, error = false)
        val usage = repo.usageToday()!!
        assertEquals("chat does not count", 2000L, usage.backgroundTokens)
        assertEquals(1200L, usage.cachedInputTokens)

        settings.update { it.copy(backgroundTokenCap = 2000) }
        repo.saveTask(task("Renew passport", dueAt = clock.peek() - HOUR))
        val llm = ScriptedLlm({ reply("NO_NOTIFICATION") })
        val orchestrator = orchestrator(llm)
        orchestrator.runHourly(gated = true)
        assertEquals(0, llm.calls)

        orchestrator.runHourly()
        assertEquals("manual runs ignore the limit", 1, llm.calls)
    }

    @Test
    fun backgroundPassesUseTheBackgroundModel() = runBlocking {
        settings.update { it.copy(backgroundModel = "small") }
        val llm = ScriptedLlm({ reply("NO_NOTIFICATION") }, { reply("hi") })
        val orchestrator = orchestrator(llm)
        orchestrator.runHourly()
        orchestrator.enqueueUserMessage("hello")
        orchestrator.processOutbox()

        assertEquals(listOf("small", "model"), llm.modelsSeen)
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

    @Test
    fun waitingChatMakesBackgroundRunStopAtItsNextRound() = runBlocking {
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
                return toolCall("get_current_time")
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
        var hourly: com.her.agent.runner.TurnResult? = null
        var chat: com.her.agent.runner.TurnResult? = null
        coroutineScope {
            val hourlyJob = launch { hourly = orch.runHourly() }
            while (started.isEmpty()) yield()
            val chatJob = launch {
                orch.enqueueUserMessage("hi")
                chat = orch.processOutbox()
            }
            delay(40)
            gate.complete(Unit)
            hourlyJob.join()
            chatJob.join()
        }
        assertEquals(listOf("hourly", "chat"), started)
        assertEquals(AgentOrchestrator.YIELDED, hourly!!.error)
        assertEquals("ok", chat!!.assistantText)
        assertEquals(0L, settings.read().lastHourlyRunAt)
    }

    @Test
    fun aChatReplyIsFollowedByOneAppraisalThatRecordsTheFeeling() = runBlocking {
        val llm = ScriptedLlm(
            { reply("I'm so sorry. I'm here with you.") },
            { toolCall("appraise", """{"userContext":"DISTRESSED","personal":true,"goalProgress":-0.6,"expectedness":0.2,"uncertainty":0.7,"confidence":0.8}""") },
        )
        val turns = orchestrator(llm, appraise = true)
        val message = turns.enqueueUserMessage("My dad is in surgery and I'm scared")
        assertEquals("I'm so sorry. I'm here with you.", turns.processOutbox()!!.assistantText)
        assertEquals(2, llm.calls)
        assertEquals(listOf("appraise"), llm.toolsSeen.last().map { it.name })
        assertEquals("DISTRESSED", repo.getCompanionRecord("EMOTION_EXPRESSION:${message.id}")!!.data().getString("userContext"))
        assertTrue(repo.getCompanionRecord("EMOTION_EVENT:USER_SHARED:${message.id}") != null)
    }

    private fun orchestrator(llm: LlmClient, appraise: Boolean = false) = AgentOrchestrator(
        repo = repo,
        llm = llm,
        llmSettings = { LlmSettings(baseUrl = "http://test", apiKey = "key", model = "model") },
        settings = settings,
        contextBuilder = contextBuilder,
        tools = tools,
        notifier = notifier,
        policy = NotificationPolicy(settings),
        retryDelayMs = { 0L },
        appraiser = if (appraise) com.her.feelings.FastAppraiser(repo, llm) { LlmSettings(baseUrl = "http://test", apiKey = "key", model = "model") } else null,
    )

    private fun task(title: String, dueAt: Long): TaskItem {
        val now = clock.peek()
        return TaskItem(newId(), title, null, TaskStatus.OPEN, dueAt, null, null, now, now, repo.deviceId, 1, null)
    }

    private fun event(title: String, startAt: Long): CalendarEvent {
        val now = clock.peek()
        return CalendarEvent(
            newId(), title, startAt, startAt + HOUR, null, null, null, CalendarSource.INTERNAL, null, now, now, repo.deviceId, 1, null,
        )
    }

    private fun reply(text: String) =
        LlmResponse(LlmMessage(role = "assistant", content = text), LlmUsage(10, 5, 1), "{}")

    private fun toolCall(name: String, arguments: String = "{}", text: String? = null) = LlmResponse(
        LlmMessage(role = "assistant", content = text, toolCalls = listOf(LlmToolCall("call_1", name, arguments))),
        LlmUsage(10, 5, 1),
        "{}",
    )

    private class ScriptedLlm(vararg steps: () -> LlmResponse) : LlmClient() {
        private val script = steps.toMutableList()
        val toolsSeen = mutableListOf<List<ToolSpec>>()
        val modelsSeen = mutableListOf<String>()
        val calls: Int get() = toolsSeen.size

        override suspend fun complete(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            client: OkHttpClient,
            webSearch: WebSearchRequest?,
        ): LlmResponse = next(settings, tools)

        override suspend fun stream(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            onDelta: (String) -> Unit,
        ): LlmResponse = next(settings, tools)

        private fun next(settings: LlmSettings, tools: List<ToolSpec>): LlmResponse {
            toolsSeen += tools
            modelsSeen += settings.model
            check(script.isNotEmpty()) { "scripted LLM ran out of responses" }
            return script.removeAt(0)()
        }
    }
}
