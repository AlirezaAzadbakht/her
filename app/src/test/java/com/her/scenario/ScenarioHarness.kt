package com.her.scenario

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.core.RelativeTimeParser
import com.her.core.newId
import com.her.core.nowMillis
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.FakeCalendarDataSource
import com.her.data.calendar.FakeGoogleCalendarClient
import com.her.data.calendar.GoogleCalendar
import com.her.data.db.HerDatabase
import com.her.data.remote.LlmClient
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
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
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
) : ToolRegistry(repo, ranker, settings, calendar, webSearch, onUserMessage, googleCalendar) {
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
    val error: String?,
)

class ScenarioHarness(
    private val spec: ScenarioSpec,
    private val llmSettings: LlmSettings,
    private val llm: LlmClient,
    attempt: Int,
) {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val settings = AppSettingsStore(context, "her_scenario_${spec.id}_${attempt}_${System.nanoTime()}")
    private val db: HerDatabase = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val repo = HerRepository(db, settings)
    val calendar = FakeCalendarDataSource(context)
    val google = FakeGoogleCalendarClient()
    private val ranker = HybridRanker(repo)
    private val googleCalendar = GoogleCalendar(repo, google)
    private val tools = RecordingToolRegistry(repo, ranker, settings, calendar, WebSearchClient(), { text ->
        persistProactive(text)
    }, googleCalendar)
    private val notifier = RecordingNotifier(context)
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
            spec.turns.forEach { turn ->
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
        val usage = repo.observeUsageToday().first()
        val bundle = runCatching { contextBuilder.build().systemBundle }.getOrDefault("")
        return HarnessOutcome(
            toolCalls = tools.calls,
            notifications = notifier.notifications,
            messages = messages,
            lastReply = reply,
            systemBundle = bundle,
            tableDump = Checks.dumpTables(repo, extraTables()),
            inputTokens = usage?.inputTokens ?: 0L,
            outputTokens = usage?.outputTokens ?: 0L,
            error = error,
        )
    }

    fun close() {
        db.close()
    }

    fun extraTables(): Map<String, List<Map<String, Any?>>> =
        mapOf(
            "system_calendar" to calendar.rows(),
            "google_calendar" to google.rows(),
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
                    updatedAt = nowMillis(),
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
        val profile = repo.getProfile()
        val zone = runCatching { ZoneId.of(profile.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        val now = Instant.ofEpochMilli(nowMillis()).atZone(zone)
        return RelativeTimeParser.parse(raw, now)?.toInstant()?.toEpochMilli()
    }

    private suspend fun persistProactive(text: String) {
        val now = nowMillis()
        repo.saveMessage(
            ChatMessage(
                id = newId(),
                role = MessageRole.ASSISTANT,
                content = text,
                createdAt = now,
                updatedAt = now,
                deviceId = repo.deviceId,
                version = 1,
                deletedAt = null,
                status = MessageStatus.SENT,
                metadataJson = """{"proactive":true}""",
            ),
        )
    }
}
