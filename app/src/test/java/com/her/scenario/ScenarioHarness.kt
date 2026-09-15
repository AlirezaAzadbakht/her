package com.her.scenario

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.core.RelativeTimeParser
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.FakeCalendarDataSource
import com.her.data.calendar.FakeGoogleCalendarClient
import com.her.data.calendar.GoogleCalendar
import com.her.data.db.HerDatabase
import com.her.data.remote.FakeWebSearchClient
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.ChatMessage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.ToolResult
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import com.her.reminders.AlarmClockPort
import com.her.reminders.FakeAlarmClock
import com.her.reminders.FakeReminderAlarms
import com.her.reminders.ReminderAlarms
import com.her.reminders.ReminderDelivery
import org.json.JSONObject

data class RecordedNotification(
    val text: String,
    val briefing: Boolean,
)

class RecordingToolRegistry(
    repo: HerRepository,
    ranker: HybridRanker,
    settings: AppSettingsStore,
    calendar: CalendarDataSource,
    webSearch: WebSearchClient,
    onUserMessage: suspend (String) -> Unit,
    googleCalendar: GoogleCalendar,
    reminderAlarms: ReminderAlarms,
    alarmClock: AlarmClockPort,
) : ToolRegistry(repo, ranker, settings, calendar, webSearch, onUserMessage, googleCalendar, reminderAlarms, alarmClock) {
    private val recorded = mutableListOf<RecordedToolCall>()
    val calls: List<RecordedToolCall> get() = recorded.toList()

    fun clear() {
        recorded.clear()
    }

    override suspend fun execute(name: String, arguments: String): ToolResult {
        val result = super.execute(name, arguments)
        recorded += RecordedToolCall(name, arguments, result.ok, result.payloadJson)
        return result
    }
}

class RecordingNotifier(context: android.content.Context) : Notifier(context) {
    private val recorded = mutableListOf<RecordedNotification>()
    val notifications: List<RecordedNotification> get() = recorded.toList()

    override fun show(text: String, briefing: Boolean) {
        recorded += RecordedNotification(text, briefing)
    }

    override fun showReminder(id: String, text: String) {
        recorded += RecordedNotification(text, briefing = false)
    }
}

data class HarnessOutcome(
    val toolCalls: List<RecordedToolCall>,
    val notifications: List<RecordedNotification>,
    val messages: List<ChatMessage>,
    val lastReply: String?,
    val systemBundle: String,
    val tableDump: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val llmCalls: Int,
    val error: String?,
    val cachedInputTokens: Long = 0,
)

class ScenarioHarness(
    private val spec: ScenarioSpec,
    private val llmSettings: LlmSettings,
    private val llm: ScenarioLlmClient,
    attempt: Int,
    startMillis: Long,
) {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    val clock = FakeClock(startMillis)
    private val settings = AppSettingsStore(context, "her_scenario_${spec.id}_${attempt}_${System.nanoTime()}")
    private val db: HerDatabase = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val repo = HerRepository(db, settings, clock)
    val calendar = FakeCalendarDataSource(context)
    val google = FakeGoogleCalendarClient()
    val webSearch = FakeWebSearchClient()
    private val ranker = HybridRanker(repo)
    private val googleCalendar = GoogleCalendar(repo, google)
    val reminderAlarms = FakeReminderAlarms()
    val alarmClock = FakeAlarmClock()
    private val tools = RecordingToolRegistry(repo, ranker, settings, calendar, webSearch, { text ->
        repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, text, MessageStatus.SENT, """{"proactive":true}"""))
    }, googleCalendar, reminderAlarms, alarmClock)
    private val notifier = RecordingNotifier(context)
    private val reminders = ReminderDelivery(repo, notifier)
    private val contextBuilder = ContextBuilder(repo, ranker, settings, calendar, googleCalendar = googleCalendar)
    private val orchestrator = AgentOrchestrator(
        repo = repo,
        llm = llm,
        llmSettings = { llmSettings },
        settings = settings,
        contextBuilder = contextBuilder,
        tools = tools,
        notifier = notifier,
        policy = NotificationPolicy(settings),
    )

    suspend fun run(): HarnessOutcome {
        var lastReply: String? = null
        var error: String? = null
        try {
            seed()
            tools.clear()
            for (turn in spec.turns) {
                val result = when (turn) {
                    is ScenarioTurn.User -> {
                        orchestrator.enqueueUserMessage(turn.text)
                        orchestrator.processOutbox()
                    }
                    is ScenarioTurn.Run -> when (turn.kind) {
                        AutonomousRun.HOURLY -> orchestrator.runHourly()
                        AutonomousRun.NIGHTLY -> orchestrator.runNightly()
                        AutonomousRun.BRIEFING -> orchestrator.runBriefing()
                    }
                    // Moving the clock stands in for AlarmManager: anything due rings before the next turn.
                    is ScenarioTurn.Advance -> {
                        clock.advance(turn.millis)
                        reminders.fireDue()
                        null
                    }
                    is ScenarioTurn.At -> {
                        val target = RelativeTimeParser.parse(turn.phrase, repo.now())?.toInstant()?.toEpochMilli()
                            ?: error("Could not parse at '${turn.phrase}'")
                        if (target < clock.peek()) error("at '${turn.phrase}' is in the past; the harness clock only moves forward")
                        clock.set(target)
                        reminders.fireDue()
                        null
                    }
                }
                if (result?.failed == true) {
                    error = result.error ?: "turn failed"
                }
                lastReply = result?.assistantText?.takeIf { it.isNotBlank() } ?: lastReply
            }
        } catch (e: Exception) {
            error = e.message ?: e::class.java.simpleName
        }
        val messages = repo.recentMessages(200)
        val reply = Checks.lastAssistantReply(messages, lastReply)
            ?: notifier.notifications.lastOrNull()?.text
        val bundle = runCatching { contextBuilder.build().systemBundle }.getOrDefault("")
        return HarnessOutcome(
            toolCalls = tools.calls,
            notifications = notifier.notifications,
            messages = messages,
            lastReply = reply,
            systemBundle = bundle,
            tableDump = Checks.dumpTables(repo, extraTables()),
            inputTokens = llm.inputTokens,
            outputTokens = llm.outputTokens,
            llmCalls = llm.calls,
            error = error,
            cachedInputTokens = llm.cachedInputTokens,
        )
    }

    fun close() {
        db.close()
    }

    fun extraTables(): Map<String, List<Map<String, Any?>>> =
        mapOf(
            "system_calendar" to calendar.rows(),
            "google_calendar" to google.rows(),
            "alarms" to alarmClock.rows(),
        )

    private suspend fun seed() {
        spec.settings.chatToolCallLimit?.let { limit ->
            settings.update { it.copy(chatToolCallLimit = limit) }
        }
        val enableCalendar = spec.settings.calendarEnabled || spec.seed.systemCalendar.isNotEmpty()
        if (enableCalendar) {
            calendar.granted = true
            settings.update { it.copy(calendarEnabled = true) }
        }
        val enableSearch = spec.settings.webSearchEnabled || spec.seed.webSearch.isNotEmpty()
        if (enableSearch) {
            settings.update { it.copy(webSearchEnabled = true) }
        }
        webSearch.seed(
            spec.seed.webSearch.map { hit ->
                FakeWebSearchClient.Fixture(hit.queryContainsAny, hit.title, hit.snippets)
            },
        )
        if (spec.seed.profile.isNotEmpty()) {
            val current = repo.getProfile()
            val fields = spec.seed.profile
            repo.saveProfile(
                current.copy(
                    userName = fields["userName"] ?: current.userName,
                    assistantName = fields["assistantName"] ?: current.assistantName,
                    timezone = fields["timezone"] ?: current.timezone,
                    preferredLanguage = fields["preferredLanguage"] ?: current.preferredLanguage,
                    country = fields["country"] ?: current.country,
                    typicalWakeTime = fields["typicalWakeTime"] ?: current.typicalWakeTime,
                    typicalSleepTime = fields["typicalSleepTime"] ?: current.typicalSleepTime,
                    occupationOrStudyContext = fields["occupationOrStudyContext"] ?: current.occupationOrStudyContext,
                    updatedAt = clock.nowMillis(),
                    version = current.version + 1,
                ),
            )
        }
        spec.seed.systemCalendar.forEach { event ->
            val start = parseSeedWhen(event.whenPhrase)
                ?: error("Could not parse system_calendar when '${event.whenPhrase}'")
            calendar.seed(event.title, start, start + 60 * 60 * 1000, event.notes)
        }
        if (spec.seed.googleCalendar.isNotEmpty()) {
            google.granted = true
            spec.seed.googleCalendar.forEach { event ->
                val start = parseSeedWhen(event.whenPhrase)
                    ?: error("Could not parse google_calendar when '${event.whenPhrase}'")
                google.seed(event.title, start, start + 60 * 60 * 1000, event.notes)
            }
        }
        spec.seed.tools.forEach { call ->
            val result = tools.execute(call.name, call.argumentsJson)
            if (!result.ok && !JSONObject(result.payloadJson).optBoolean("ok", false)) {
                error("Seed tool ${call.name} failed: ${result.payloadJson}")
            }
        }
    }

    private suspend fun parseSeedWhen(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        return RelativeTimeParser.parse(raw, repo.now())?.toInstant()?.toEpochMilli()
    }
}
