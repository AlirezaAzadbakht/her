package com.her.agent.tools

import com.her.core.RelativeTimeParser
import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.GoogleCalendar
import com.her.data.calendar.GoogleCalendarClient
import com.her.data.calendar.SystemCalendar
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.MemoryRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.CalendarSource
import com.her.domain.Commitment
import com.her.domain.CommitmentStatus
import com.her.domain.GoalStatus
import com.her.domain.GroceryStatus
import com.her.domain.OpenLoopStatus
import com.her.domain.ReminderStatus
import com.her.domain.TaskItem
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
) {
    private val handlers = linkedMapOf<String, Pair<ToolSpec, ToolHandler>>()
    internal val systemCalendar = SystemCalendar(repo, calendar, settings)
    internal val googleCalendar = googleCalendar

    internal fun nowMillis(): Long = repo.clock.nowMillis()

    init {
        registerAll()
    }

    fun specs(): List<ToolSpec> {
        val all = handlers.values.map { it.first }
        return if (settings.read().webSearchEnabled) all else all.filter { it.name != "web_search" }
    }

    open suspend fun execute(name: String, arguments: String): ToolResult {
        val spec = handlers[name] ?: return ToolResult(name, false, jsonObjectOf("error" to "Unknown tool: $name").toString())
        val args = try {
            if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
        } catch (e: JSONException) {
            val error = "Arguments were not a valid JSON object (${e.message}). Resend the call with a JSON object."
            return ToolResult(name, false, jsonObjectOf("ok" to false, "error" to error).toString())
        }
        return try {
            validateRequired(spec.first, args)
            val payload = spec.second.invoke(args)
            repo.logActivity("tool", name, payload.toString().take(2000))
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

    private fun registerAll() {
        registerMemoryTools()
        registerEntityTools()
        registerAgentTools()
        registerCalendarTools()
        registerMessagingTools()
        registerReminderTools()
    }

    /** Reverses a record created during a turn, for the undo on a write receipt. False when there is nothing to undo. */
    suspend fun undo(entityType: String, id: String): Boolean {
        val now = nowMillis()
        when (entityType) {
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
