package com.her.agent.tools

import com.her.reminders.rearmAll

import com.her.core.COMMON_DONE
import com.her.core.COMMON_DROPPED
import com.her.core.RelativeTimeParser
import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.normalizeStatusKey
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.GoogleCalendar
import com.her.data.calendar.GoogleCalendarClient
import com.her.data.calendar.SystemCalendar
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.MemoryRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.AgentRunType
import com.her.domain.CalendarSource
import com.her.domain.Commitment
import com.her.domain.CommitmentStatus
import com.her.domain.ConfirmationKind
import com.her.domain.GoalStatus
import com.her.domain.GroceryStatus
import com.her.domain.MessageRole
import com.her.domain.OpenLoopStatus
import com.her.domain.PendingConfirmation
import com.her.domain.ReminderStatus
import com.her.domain.TaskItem
import com.her.focus.FocusTimer
import com.her.reminders.AlarmClockPort
import com.her.reminders.ReminderAlarms
import com.her.domain.TaskStatus
import com.her.domain.ToolResult
import com.her.domain.ToolSpec
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONException
import org.json.JSONObject

fun interface ToolHandler {
    suspend fun invoke(args: JSONObject): JSONObject
}

open class ToolRegistry(
    internal val repo: HerRepository,
    internal val ranker: MemoryRanker,
    internal val settings: AppSettingsStore,
    internal val calendar: CalendarDataSource,
    internal val webSearch: WebSearchClient,
    internal val onUserMessage: suspend (String) -> Unit = {},
    googleCalendar: GoogleCalendar = GoogleCalendar(repo, GoogleCalendarClient()),
    internal val reminderAlarms: ReminderAlarms = ReminderAlarms.NONE,
    internal val alarmClock: AlarmClockPort = AlarmClockPort.UNAVAILABLE,
    internal val focus: FocusTimer? = null,
) {
    private val handlers = linkedMapOf<String, Pair<ToolSpec, ToolHandler>>()
    internal val systemCalendar = SystemCalendar(repo, calendar, settings)
    internal val googleCalendar = googleCalendar
    /** Chat may use every tool; autonomous runs cannot wipe or rewrite identity. */
    var runType: AgentRunType = AgentRunType.CHAT

    internal fun nowMillis(): Long = repo.clock.nowMillis()

    init {
        registerAll()
    }

    fun specs(): List<ToolSpec> = specsFor(AgentRunType.CHAT)

    fun specsFor(type: AgentRunType): List<ToolSpec> {
        val all = handlers.values.map { it.first }
        val visible = (if (settings.read().webSearchEnabled) all else all.filter { it.name != "web_search" })
            .filter { type == AgentRunType.NIGHTLY || it.name !in REFLECTION_ONLY_TOOLS }
        return if (type == AgentRunType.CHAT) visible else visible.filter { it.name !in AUTONOMOUS_BLOCKED_TOOLS }
    }

    open suspend fun execute(name: String, arguments: String): ToolResult {
        if (runType != AgentRunType.CHAT && name in AUTONOMOUS_BLOCKED_TOOLS) {
            return ToolResult(name, false, jsonObjectOf("ok" to false, "error" to "Not available during autonomous runs").toString())
        }
        val spec = handlers[name] ?: return ToolResult(name, false, jsonObjectOf("error" to "Unknown tool: $name").toString())
        val args = try {
            if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
        } catch (e: JSONException) {
            val error = "Arguments were not a valid JSON object (${e.message}). Resend the call with a JSON object."
            return ToolResult(name, false, jsonObjectOf("ok" to false, "error" to error).toString())
        }
        return try {
            validateRequired(spec.first, args)
            validateTypes(spec.first, args)
            if (name in companionToolNames || name in wonderingToolNames || name in understandingToolNames || name in supportToolNames || name in emotionalToolNames) validateCompanionSchema(spec.first, args)
            if (name in emotionalToolNames) {
                args.keys().forEach { key ->
                    val v = args.get(key)
                    if (v is String && v.length > 800) throw ToolValidationException("Field too long: $key")
                    if (key in setOf("confidence", "intensity", "contribution", "expectedness", "uncertainty") && (v !is Number || v.toDouble() !in 0.0..1.0)) throw ToolValidationException("$key must be between 0 and 1")
                    if (key == "goalProgress" && (v !is Number || v.toDouble() !in -1.0..1.0)) throw ToolValidationException("goalProgress must be between -1 and 1")
                    if (key in setOf("offset", "expectedVersion") && (v !is Number || v.toDouble() != v.toLong().toDouble())) throw ToolValidationException("$key must be an integer")
                }
            }
            val payload = spec.second.invoke(args)
            if (name in setOf("set_reminder", "save_work_session")) {
                try { captureEmotionalWork(name, payload) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { repo.logActivity("emotion", "Could not capture work evidence", null) }
            }
            if (name == "update_user_profile") reminderAlarms.rearmAll(repo)
            repo.logActivity("tool", name, if (name in companionToolNames || name in wonderingToolNames || name in understandingToolNames || name in supportToolNames || name in emotionalToolNames) null else payload.toString().take(2000))
            ToolResult(name, payload.optBoolean("ok", true), payload.toString())
        } catch (e: ToolValidationException) {
            ToolResult(name, false, jsonObjectOf("ok" to false, "error" to e.message).toString())
        } catch (e: Exception) {
            ToolResult(name, false, jsonObjectOf("ok" to false, "error" to (e.message ?: "tool failed")).toString())
        }
    }

    internal fun register(name: String, description: String, schema: String, handler: ToolHandler) {
        handlers[name] = ToolSpec(name, description, schema) to handler
    }

    private fun validateRequired(spec: ToolSpec, args: JSONObject) {
        val required = JSONObject(spec.parametersJson).optJSONArray("required") ?: return
        for (i in 0 until required.length()) {
            val key = required.getString(i)
            if (!args.has(key) || args.isNull(key) || args.optString(key).isBlank() && args.opt(key) is String) {
                throw ToolValidationException("Missing required field: $key")
            }
        }
    }

    private fun validateTypes(spec: ToolSpec, args: JSONObject) {
        val properties = JSONObject(spec.parametersJson).optJSONObject("properties") ?: return
        args.keys().forEach { key ->
            val field = properties.optJSONObject(key) ?: return@forEach
            if (args.isNull(key)) return@forEach
            val value = args.get(key)
            val valid = when (field.optString("type")) {
                "string" -> value is String
                "boolean" -> value is Boolean
                "number" -> value is Number && value.toDouble().isFinite()
                "array" -> value is org.json.JSONArray && (0 until value.length()).all { value.get(it) is String }
                else -> true
            }
            if (!valid) throw ToolValidationException("Invalid type for $key")
            if (!enumAllowed(field, value)) throw ToolValidationException("Invalid value for $key")
        }
    }

    private fun enumAllowed(field: JSONObject, value: Any): Boolean {
        val values = field.optJSONArray("enum") ?: return true
        val text = value.toString()
        if ((0 until values.length()).any { values.get(it).toString() == text }) return true
        val mapped = COMMON_DONE[normalizeStatusKey(text)]
            ?: COMMON_DROPPED[normalizeStatusKey(text)]
            ?: normalizeStatusKey(text)
        return (0 until values.length()).any { values.get(it).toString() == mapped }
    }

    /**
     * A confirmId is only valid after a later user message. Same-turn retries from the model
     * cannot consume the token they just created.
     */
    internal suspend fun consumeConfirmation(confirmId: String, kind: ConfirmationKind): PendingConfirmation {
        val pending = repo.getConfirmation(confirmId)?.takeIf { it.kind == kind }
            ?: throw ToolValidationException("Confirmation not found")
        val lastUser = repo.recentMessages(48).lastOrNull { it.role == MessageRole.USER }
            ?: throw ToolValidationException("Wait for them to confirm before using this confirmId")
        if (pending.createdAt >= lastUser.createdAt) {
            throw ToolValidationException("Wait for them to confirm before using this confirmId")
        }
        repo.deleteConfirmation(confirmId)
        return pending
    }

    private fun validateCompanionSchema(spec: ToolSpec, args: JSONObject) {
        val properties = JSONObject(spec.parametersJson).getJSONObject("properties")
        args.keys().forEach { key ->
            val field = properties.optJSONObject(key) ?: throw ToolValidationException("Unknown field: $key")
            if (!args.isNull(key)) {
                val value = args.get(key)
                val valid = when (field.getString("type")) {
                    "string" -> value is String
                    "boolean" -> value is Boolean
                    "number" -> value is Number && value.toDouble().isFinite() && (spec.name in emotionalToolNames || value.toDouble() == value.toLong().toDouble())
                    "array" -> value is org.json.JSONArray && (0 until value.length()).all { value.get(it) is String }
                    else -> false
                }
                if (!valid) throw ToolValidationException("Invalid type for $key")
                field.optJSONArray("enum")?.let { values ->
                    if ((0 until values.length()).none { values.get(it) == value }) throw ToolValidationException("Invalid value for $key")
                }
            }
        }
    }

    private fun registerAll() {
        registerMemoryTools()
        registerEntityTools()
        registerAgentTools()
        registerCalendarTools()
        registerMessagingTools()
        registerReminderTools()
        registerLearningTools()
        registerCompanionTools()
        registerUnderstandingTools()
        registerWonderingTools()
        registerSupportTools()
        registerEmotionalTools()
        registerFocusTools()
    }

    /** Reverses a record created during a turn, for the undo on a write receipt. False when there is nothing to undo. */
    suspend fun undo(entityType: String, id: String): Boolean {
        val now = nowMillis()
        when (entityType) {
            "companion_records" -> {
                repo.getCompanionRecord(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.forgetCompanion(id)
            }
            "long_term_memories" -> {
                repo.getLong(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.deleteLong(id)
            }
            "short_term_memories" -> {
                repo.getShort(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.deleteShort(id)
            }
            "tasks" -> {
                val item = repo.getTask(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveTask(item.copy(status = TaskStatus.DROPPED, deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "commitments" -> {
                val item = repo.getCommitment(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveCommitment(item.copy(status = CommitmentStatus.DROPPED, deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "groceries" -> {
                val item = repo.getGrocery(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveGrocery(item.copy(status = GroceryStatus.DROPPED, deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "goals" -> {
                val item = repo.getGoal(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveGoal(item.copy(status = GoalStatus.DROPPED, deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "open_loops" -> {
                val item = repo.getOpenLoop(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveOpenLoop(item.copy(status = OpenLoopStatus.DROPPED, deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "routines" -> {
                val item = repo.getRoutine(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveRoutine(item.copy(deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "important_dates" -> {
                val item = repo.getImportantDate(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveImportantDate(item.copy(deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "recurring_responsibilities" -> {
                val item = repo.getResponsibility(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveResponsibility(item.copy(deletedAt = now, updatedAt = now, version = item.version + 1))
            }
            "calendar_events" -> {
                val event = repo.getCalendarEvent(id)?.takeIf { it.deletedAt == null } ?: return false
                if (event.source == CalendarSource.GOOGLE) return false
                if (event.source == CalendarSource.SYSTEM) systemCalendar.delete(event)
                repo.deleteCalendarEvent(id)
            }
            "reminders" -> {
                val item = repo.getReminder(id)?.takeIf { it.deletedAt == null } ?: return false
                repo.saveReminder(item.copy(status = ReminderStatus.CANCELLED, deletedAt = now, updatedAt = now, version = item.version + 1))
                reminderAlarms.disarm(id)
            }
            else -> return false
        }
        repo.logActivity("undo", "$entityType $id")
        return true
    }

    internal suspend fun nowZoned(): ZonedDateTime {
        val profile = repo.getProfile()
        val zone = runCatching { ZoneId.of(profile.timezone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
        return Instant.ofEpochMilli(nowMillis()).atZone(zone)
    }

    internal suspend fun parseWhen(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return it }
        return RelativeTimeParser.parse(raw, nowZoned())?.toInstant()?.toEpochMilli()
    }

    internal suspend fun requireTask(idOrTitle: String): TaskItem {
        repo.getTask(idOrTitle)?.let { return it }
        val all = repo.tasks()
        all.firstOrNull { it.id.equals(idOrTitle, ignoreCase = true) }?.let { return it }
        val exact = all.filter { it.title.equals(idOrTitle, ignoreCase = true) }
        if (exact.size == 1) return exact.first()
        val loose = all.filter { it.title.contains(idOrTitle, ignoreCase = true) }
        return loose.singleOrNull()
            ?: throw ToolValidationException(
                if (exact.isEmpty() && loose.isEmpty()) "Task not found: $idOrTitle"
                else "Multiple tasks match '$idOrTitle'; pass the id from context",
            )
    }

    internal suspend fun requireCommitment(idOrTitle: String): Commitment {
        repo.getCommitment(idOrTitle)?.let { return it }
        val all = repo.commitments()
        val exact = all.filter { it.title.equals(idOrTitle, ignoreCase = true) }
        if (exact.size == 1) return exact.first()
        val loose = all.filter { it.title.contains(idOrTitle, ignoreCase = true) }
        return loose.singleOrNull()
            ?: throw ToolValidationException(
                if (exact.isEmpty() && loose.isEmpty()) "Commitment not found: $idOrTitle"
                else "Multiple commitments match '$idOrTitle'; pass the id from context",
            )
    }

    /** A time the model explicitly sent must parse; silently keeping the old value would hide the failure. */
    internal suspend fun requiredWhen(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return parseWhen(raw) ?: throw ToolValidationException("Could not understand the time: $raw")
    }
}

internal val AUTONOMOUS_BLOCKED_TOOLS = setOf("forget_memory", "delete_calendar_event", "update_user_profile", "ask_wondering")

/** Filing feelings and writing episodes is the nightly review's work; chat and the other passes only feel (after the reply) and recall. */
internal val REFLECTION_ONLY_TOOLS = emotionalToolNames - "get_emotional_state" + "set_feeling" + "save_episode"
