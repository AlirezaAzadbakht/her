package com.her.agent.tools

import com.her.core.COMMON_DONE
import com.her.core.COMMON_DROPPED
import com.her.core.RelativeTimeParser
import com.her.core.ToolValidationException
import com.her.core.clamp01
import com.her.core.formatNaturalDate
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.normalizeDateIso
import com.her.core.nowMillis
import com.her.core.optDoubleOr
import com.her.core.optLongOrNull
import com.her.core.optStringList
import com.her.core.optStringOrNull
import com.her.core.parseEnum
import com.her.core.requiredString
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.CalendarWindows
import com.her.data.calendar.GoogleCalendar
import com.her.data.calendar.GoogleCalendarClient
import com.her.data.calendar.SystemCalendar
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.repository.toJson
import com.her.data.retrieval.MemoryRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.AgentQueueItem
import com.her.domain.AgentStateEntry
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import com.her.domain.Commitment
import com.her.domain.CommitmentStatus
import com.her.domain.ConfirmationKind
import com.her.domain.Goal
import com.her.domain.GoalStatus
import com.her.domain.GroceryItem
import com.her.domain.GroceryStatus
import com.her.domain.ImportantDate
import com.her.domain.LongTermMemory
import com.her.domain.MemoryRelationship
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.OpenLoop
import com.her.domain.OpenLoopStatus
import com.her.domain.PendingConfirmation
import com.her.domain.Person
import com.her.domain.Project
import com.her.domain.ProjectStatus
import com.her.domain.QueueStatus
import com.her.domain.RecurringResponsibility
import com.her.domain.Routine
import com.her.domain.ShortTermMemory
import com.her.domain.TaskItem
import com.her.domain.TaskStatus
import com.her.domain.ToolResult
import com.her.domain.UserUnderstanding
import com.her.domain.ToolSpec
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

fun interface ToolHandler {
    suspend fun invoke(args: JSONObject): JSONObject
}

open class ToolRegistry(
    private val repo: HerRepository,
    private val ranker: MemoryRanker,
    private val settings: AppSettingsStore,
    private val calendar: CalendarDataSource,
    private val webSearch: WebSearchClient,
    private val onUserMessage: suspend (String) -> Unit = {},
    googleCalendar: GoogleCalendar = GoogleCalendar(repo, GoogleCalendarClient()),
) {
    private val handlers = linkedMapOf<String, Pair<ToolSpec, ToolHandler>>()
    private val systemCalendar = SystemCalendar(repo, calendar, settings)
    private val googleCalendar = googleCalendar

    init {
        registerAll()
    }

    fun specs(): List<ToolSpec> {
        val all = handlers.values.map { it.first }
        return if (settings.read().webSearchEnabled) all else all.filter { it.name != "web_search" }
    }

    open suspend fun execute(name: String, arguments: String): ToolResult {
        val spec = handlers[name] ?: return ToolResult(name, false, jsonObjectOf("error" to "Unknown tool: $name").toString())
        return try {
            val args = if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
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

    private fun register(name: String, description: String, schema: String, handler: ToolHandler) {
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
        register(
            "get_current_time",
            "Return the current local date and time.",
            objSchema(),
        ) {
            val profile = repo.getProfile()
            val zone = runCatching { ZoneId.of(profile.timezone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
            val now = Instant.ofEpochMilli(nowMillis()).atZone(zone)
            jsonObjectOf(
                "ok" to true,
                "iso" to now.toOffsetDateTime().toString(),
                "zone" to zone.id,
                "epochMillis" to nowMillis(),
            )
        }

        register(
            "search_memory",
            "Search short-term and long-term memories using text relevance, importance, confidence, and recency.",
            objSchema(
                "query" to str("Search query"),
                "memoryTypes" to arr("Optional: short_term, long_term"),
                "limit" to num("Max results"),
                required = listOf("query"),
            ),
        ) { args ->
            val hits = ranker.search(
                query = args.requiredString("query"),
                memoryTypes = args.optStringList("memoryTypes"),
                limit = args.optInt("limit", 8),
            )
            jsonObjectOf("ok" to true, "results" to JSONArray().apply {
                hits.forEach { hit ->
                    put(
                        JSONObject()
                            .put("id", hit.id)
                            .put("type", hit.memoryType)
                            .put("content", hit.content)
                            .put("score", hit.score)
                            .put("importance", hit.importance)
                            .put("confidence", hit.confidence)
                            .put("source", hit.source)
                            .put("sourceMessageId", hit.sourceMessageId)
                            .put("derivedFrom", hit.derivedFromJson),
                    )
                }
            })
        }

        register(
            "remember",
            "Store a fact, preference, or household spec. Use short_term for temporary/uncertain context; long_term for durable facts. Do not use this for todos — that is create_task or add_grocery.",
            objSchema(
                "content" to str("Memory text"),
                "scope" to str("short_term or long_term"),
                "type" to str("Optional type/category"),
                "confidence" to num("0-1"),
                "importance" to num("0-1"),
                "source" to str("USER_EXPLICIT or AGENT_INFERENCE"),
                "sourceMessageId" to str("Origin message id"),
                "expiresAt" to num("Optional expiry epoch millis"),
                required = listOf("content"),
            ),
        ) { args ->
            val scope = args.optString("scope", "short_term")
            val source = runCatching { MemorySource.valueOf(args.optString("source", "AGENT_INFERENCE")) }.getOrDefault(MemorySource.AGENT_INFERENCE)
            val id = newId()
            val now = nowMillis()
            if (scope == "long_term") {
                repo.upsertLong(
                    LongTermMemory(
                        id = id,
                        content = args.requiredString("content"),
                        category = args.optString("type", "general"),
                        confidence = clamp01(args.optDoubleOr("confidence", 0.7)),
                        importance = clamp01(args.optDoubleOr("importance", 0.5)),
                        createdAt = now,
                        updatedAt = now,
                        lastConfirmedAt = if (source == MemorySource.USER_EXPLICIT) now else null,
                        source = source,
                        sourceMessageId = args.optStringOrNull("sourceMessageId"),
                        derivedFromJson = args.optStringOrNull("derivedFrom"),
                        validFrom = now,
                        validUntil = null,
                        status = MemoryStatus.ACTIVE,
                        metadataJson = null,
                        deviceId = repo.deviceId,
                        version = 1,
                        deletedAt = null,
                    ),
                )
            } else {
                repo.upsertShort(
                    ShortTermMemory(
                        id = id,
                        content = args.requiredString("content"),
                        type = args.optString("type", "context"),
                        confidence = clamp01(args.optDoubleOr("confidence", 0.6)),
                        importance = clamp01(args.optDoubleOr("importance", 0.4)),
                        createdAt = now,
                        updatedAt = now,
                        expiresAt = args.optLongOrNull("expiresAt"),
                        sourceMessageId = args.optStringOrNull("sourceMessageId"),
                        source = source,
                        metadataJson = null,
                        deviceId = repo.deviceId,
                        version = 1,
                        deletedAt = null,
                    ),
                )
            }
            jsonObjectOf("ok" to true, "id" to id, "scope" to scope)
        }

        register(
            "update_memory",
            "Update an existing short-term or long-term memory. Use status=HISTORICAL instead of deleting old facts.",
            objSchema(
                "id" to str("Memory id"),
                "scope" to str("short_term or long_term"),
                "content" to str("Replacement text"),
                "confidence" to num(),
                "importance" to num(),
                "status" to str("ACTIVE, HISTORICAL, ARCHIVED"),
                required = listOf("id"),
            ),
        ) { args ->
            val id = args.requiredString("id")
            val now = nowMillis()
            val short = repo.getShort(id)
            val long = repo.getLong(id)
            when {
                short != null -> {
                    repo.upsertShort(
                        short.copy(
                            content = args.optStringOrNull("content") ?: short.content,
                            confidence = args.optDoubleOr("confidence", short.confidence),
                            importance = args.optDoubleOr("importance", short.importance),
                            updatedAt = now,
                            version = short.version + 1,
                        ),
                    )
                }
                long != null -> {
                    repo.upsertLong(
                        long.copy(
                            content = args.optStringOrNull("content") ?: long.content,
                            confidence = args.optDoubleOr("confidence", long.confidence),
                            importance = args.optDoubleOr("importance", long.importance),
                            status = parseEnum<MemoryStatus>(args.optStringOrNull("status")) ?: long.status,
                            updatedAt = now,
                            version = long.version + 1,
                        ),
                    )
                }
                else -> throw ToolValidationException("Memory not found: $id")
            }
            jsonObjectOf("ok" to true, "id" to id)
        }

        register(
            "forget_memory",
            "Forget one memory, or request bulk forget (requires confirmation).",
            objSchema(
                "id" to str("Memory id"),
                "everything" to bool("If true, request confirmation to forget all personal knowledge"),
                "confirmId" to str("Confirmation id from a previous request"),
                required = emptyList(),
            ),
        ) { args ->
            if (args.optBoolean("everything")) {
                val confirmId = args.optStringOrNull("confirmId")
                if (confirmId == null) {
                    val pending = PendingConfirmation(newId(), ConfirmationKind.BULK_FORGET, "Forget everything Her knows about you", "{}", nowMillis())
                    repo.saveConfirmation(pending)
                    return@register jsonObjectOf("ok" to false, "needsConfirmation" to true, "confirmId" to pending.id, "summary" to pending.summary)
                }
                val pending = repo.getConfirmation(confirmId) ?: throw ToolValidationException("Confirmation not found")
                repo.deleteConfirmation(confirmId)
                repo.activeLong().forEach { repo.deleteLong(it.id) }
                repo.activeShort().forEach { repo.deleteShort(it.id) }
                repo.logActivity("memory", "Bulk forget confirmed ${pending.id}")
                return@register jsonObjectOf("ok" to true, "forgotten" to "all")
            }
            val id = args.requiredString("id")
            if (repo.getShort(id) != null) repo.deleteShort(id) else repo.deleteLong(id)
            jsonObjectOf("ok" to true, "id" to id)
        }

        register("search_chat_history", "Search the lifetime conversation.", objSchema("query" to str(), "limit" to num(), required = listOf("query"))) { args ->
            val found = repo.searchChat(args.requiredString("query"), args.optInt("limit", 10))
            jsonObjectOf("ok" to true, "messages" to JSONArray().apply {
                found.forEach { put(JSONObject().put("id", it.id).put("role", it.role.name).put("content", it.content).put("createdAt", it.createdAt)) }
            })
        }

        entityTools()
        agentTools()
        calendarTools()
        register("web_search", "Search the public web for current external facts. Do not use for personal memory.", objSchema("query" to str(), required = listOf("query"))) { args ->
            val profile = repo.getProfile()
            JSONObject(
                webSearch.search(
                    query = args.requiredString("query"),
                    settings = settings.read(),
                    country = profile.country,
                    timezone = profile.timezone,
                ),
            )
        }
        register("send_user_message", "Deliver a proactive message into the conversation. Use rarely.", objSchema("content" to str(), required = listOf("content"))) { args ->
            val text = args.requiredString("content").trim()
            if (text.equals("NO_NOTIFICATION", ignoreCase = true)) {
                return@register jsonObjectOf("ok" to true, "decision" to "NO_NOTIFICATION")
            }
            onUserMessage(text)
            jsonObjectOf("ok" to true)
        }
    }

    private fun entityTools() {
        register("get_person", "Get a person by id or name.", objSchema("id" to str(), "name" to str())) { args ->
            val person = args.optStringOrNull("id")?.let { repo.getPerson(it) }
                ?: args.optStringOrNull("name")?.let { q -> repo.searchPeople(q).firstOrNull() }
            jsonObjectOf("ok" to true, "person" to person?.let { JSONObject(it.toJson()) })
        }
        register("search_people", "Search people.", objSchema("query" to str(), required = listOf("query"))) { args ->
            jsonObjectOf("ok" to true, "people" to JSONArray(repo.searchPeople(args.requiredString("query")).map { JSONObject(it.toJson()) }))
        }
        register(
            "update_person",
            "Create or update a person.",
            objSchema(
                "id" to str(),
                "name" to str(),
                "relationship" to str(),
                "birthday" to str("Gregorian or Jalali date; it is normalized to ISO before storage"),
                "importantNotes" to str(),
                "preferences" to str(),
                "confidence" to num(),
            ),
        ) { args ->
            val now = nowMillis()
            val birthday = normalizeDateIso(args.optStringOrNull("birthday"), nowZoned())
            val existing = args.optStringOrNull("id")?.let { repo.getPerson(it) }
                ?: args.optStringOrNull("name")?.let { n -> repo.people().firstOrNull { it.name.equals(n, true) } }
            val person = (existing ?: Person(newId(), args.requiredString("name"), null, null, null, null, now, 0.8, now, now, repo.deviceId, 1, null)).copy(
                name = args.optStringOrNull("name") ?: existing?.name ?: args.requiredString("name"),
                relationship = args.optStringOrNull("relationship") ?: existing?.relationship,
                birthday = birthday ?: existing?.birthday,
                importantNotes = args.optStringOrNull("importantNotes") ?: existing?.importantNotes,
                preferences = args.optStringOrNull("preferences") ?: existing?.preferences,
                confidence = args.optDoubleOr("confidence", existing?.confidence ?: 0.8),
                lastMentioned = now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
            repo.savePerson(person)
            birthday?.let { bday ->
                val title = "${person.name}'s birthday"
                val existingDate = repo.importantDates().firstOrNull { it.relatedPersonId == person.id || it.title.equals(title, true) }
                repo.saveImportantDate(
                    (existingDate ?: ImportantDate(newId(), title, bday, "yearly", person.id, null, 0.9, now, now, repo.deviceId, 1, null)).copy(
                        dateIso = bday,
                        relatedPersonId = person.id,
                        updatedAt = now,
                        version = (existingDate?.version ?: 0) + 1,
                    ),
                )
            }
            jsonObjectOf("ok" to true, "id" to person.id)
        }

        register("get_project", "Get a project.", objSchema("id" to str(), "name" to str())) { args ->
            val project = args.optStringOrNull("id")?.let { repo.getProject(it) }
                ?: args.optStringOrNull("name")?.let { n -> repo.searchProjects(n).firstOrNull() }
            jsonObjectOf("ok" to true, "project" to project?.let { JSONObject(it.toJson()) })
        }
        register("search_projects", "Search projects.", objSchema("query" to str(), required = listOf("query"))) { args ->
            jsonObjectOf("ok" to true, "projects" to JSONArray(repo.searchProjects(args.requiredString("query")).map { JSONObject(it.toJson()) }))
        }
        register("update_project", "Create or update a project. Ideas can live in description/summary or related memories.", objSchema("id" to str(), "name" to str(), "description" to str(), "summary" to str(), "status" to str(), "importance" to num())) { args ->
            val now = nowMillis()
            val existing = args.optStringOrNull("id")?.let { repo.getProject(it) }
                ?: args.optStringOrNull("name")?.let { n -> repo.projects().firstOrNull { it.name.equals(n, true) } }
            val project = (existing ?: Project(newId(), args.requiredString("name"), null, ProjectStatus.ACTIVE, null, 0.5, now, now, repo.deviceId, 1, null)).copy(
                name = args.optStringOrNull("name") ?: existing?.name ?: args.requiredString("name"),
                description = args.optStringOrNull("description") ?: existing?.description,
                summary = args.optStringOrNull("summary") ?: existing?.summary,
                status = parseEnum<ProjectStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED + mapOf("PAUSED" to "PAUSED")) ?: existing?.status ?: ProjectStatus.ACTIVE,
                importance = args.optDoubleOr("importance", existing?.importance ?: 0.5),
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
            repo.saveProject(project)
            jsonObjectOf("ok" to true, "id" to project.id)
        }

        register("get_goals", "List goals.", objSchema()) { jsonObjectOf("ok" to true, "goals" to JSONArray(repo.goals().map { JSONObject(it.toJson()) })) }
        register("create_goal", "Create a goal.", objSchema("title" to str(), "description" to str(), "targetDate" to str(), "relatedProjectId" to str(), "priority" to num(), required = listOf("title"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveGoal(
                Goal(id, args.requiredString("title"), args.optStringOrNull("description"), GoalStatus.ACTIVE, args.optDoubleOr("priority", 0.6), parseWhen(args.optStringOrNull("targetDate")), args.optStringOrNull("relatedProjectId"), null, now, now, repo.deviceId, 1, null),
            )
            jsonObjectOf("ok" to true, "id" to id)
        }
        register("update_goal", "Update a goal.", objSchema("id" to str(), "title" to str(), "status" to str(), "progressSummary" to str(), "priority" to num(), required = listOf("id"))) { args ->
            val existing = repo.getGoal(args.requiredString("id")) ?: throw ToolValidationException("Goal not found")
            repo.saveGoal(existing.copy(
                title = args.optStringOrNull("title") ?: existing.title,
                status = parseEnum<GoalStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED) ?: existing.status,
                progressSummary = args.optStringOrNull("progressSummary") ?: existing.progressSummary,
                priority = args.optDoubleOr("priority", existing.priority),
                updatedAt = nowMillis(),
                version = existing.version + 1,
            ))
            jsonObjectOf("ok" to true)
        }

        register("get_tasks", "List tasks.", objSchema()) { jsonObjectOf("ok" to true, "tasks" to JSONArray(repo.tasks().map { JSONObject(it.toJson()) })) }
        register("create_task", "Create a task only when they intend to do something. Household facts and specs belong in remember, not here.", objSchema("title" to str(), "description" to str(), "dueAt" to str(), required = listOf("title"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveTask(TaskItem(id, args.requiredString("title"), args.optStringOrNull("description"), TaskStatus.OPEN, parseWhen(args.optStringOrNull("dueAt")), args.optStringOrNull("relatedProjectId"), args.optStringOrNull("relatedGoalId"), now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
        register(
            "update_task",
            "Update a task. id may be the task id from context or the exact title. status: OPEN, DONE, DROPPED. cancelled/canceled maps to DROPPED.",
            objSchema("id" to str("Task id or title"), "status" to str("OPEN, DONE, DROPPED"), "title" to str(), required = listOf("id")),
        ) { args ->
            val existing = requireTask(args.requiredString("id"))
            val status = parseEnum<TaskStatus>(
                args.optStringOrNull("status"),
                COMMON_DONE + COMMON_DROPPED + mapOf("TODO" to "OPEN", "PENDING" to "OPEN"),
            ) ?: existing.status
            repo.saveTask(
                existing.copy(
                    title = args.optStringOrNull("title") ?: existing.title,
                    status = status,
                    updatedAt = nowMillis(),
                    version = existing.version + 1,
                    deletedAt = if (status == TaskStatus.DROPPED) nowMillis() else existing.deletedAt,
                ),
            )
            jsonObjectOf("ok" to true, "id" to existing.id, "status" to status.name)
        }

        register("get_commitments", "List commitments.", objSchema()) { jsonObjectOf("ok" to true, "commitments" to JSONArray(repo.commitments().map { JSONObject(it.toJson()) })) }
        register("create_commitment", "Create a commitment the person actually promised.", objSchema("title" to str(), "dueAt" to str(), "promisedTo" to str(), required = listOf("title"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveCommitment(Commitment(id, args.requiredString("title"), args.optStringOrNull("description"), CommitmentStatus.OPEN, parseWhen(args.optStringOrNull("dueAt")), args.optStringOrNull("promisedTo"), null, now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
        register("update_commitment", "Update a commitment.", objSchema("id" to str(), "status" to str(), "title" to str(), required = listOf("id"))) { args ->
            val existing = repo.getCommitment(args.requiredString("id")) ?: throw ToolValidationException("Commitment not found")
            repo.saveCommitment(existing.copy(title = args.optStringOrNull("title") ?: existing.title, status = parseEnum<CommitmentStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED + mapOf("MISSED" to "MISSED")) ?: existing.status, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }

        register("get_open_loops", "List unfinished threads.", objSchema()) { jsonObjectOf("ok" to true, "openLoops" to JSONArray(repo.openLoops().map { JSONObject(it.toJson()) })) }
        register("create_open_loop", "Record an unfinished thread. Use importance and confidence; skip trivial unfinished sentences.", objSchema("description" to str(), "importance" to num(), "confidence" to num(), required = listOf("description"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveOpenLoop(OpenLoop(id, args.requiredString("description"), OpenLoopStatus.OPEN, args.optDoubleOr("importance", 0.5), args.optDoubleOr("confidence", 0.6), args.optStringOrNull("relatedEntityType"), args.optStringOrNull("relatedEntityId"), now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
        register("update_open_loop", "Update an open loop.", objSchema("id" to str(), "status" to str(), "description" to str(), required = listOf("id"))) { args ->
            val existing = repo.getOpenLoop(args.requiredString("id")) ?: throw ToolValidationException("Open loop not found")
            repo.saveOpenLoop(existing.copy(description = args.optStringOrNull("description") ?: existing.description, status = parseEnum<OpenLoopStatus>(args.optStringOrNull("status"), COMMON_DROPPED + mapOf("CLOSED" to "CLOSED", "DONE" to "CLOSED")) ?: existing.status, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }
        register("close_open_loop", "Close an open loop.", objSchema("id" to str(), required = listOf("id"))) { args ->
            val existing = repo.getOpenLoop(args.requiredString("id")) ?: throw ToolValidationException("Open loop not found")
            repo.saveOpenLoop(existing.copy(status = OpenLoopStatus.CLOSED, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }

        register("get_routines", "List routines.", objSchema()) { jsonObjectOf("ok" to true, "routines" to JSONArray(repo.routines().map { JSONObject(it.toJson()) })) }
        register("create_routine", "Create a routine. Inferred routines should have lower confidence.", objSchema("title" to str(), "schedule" to str(), "confidence" to num(), required = listOf("title"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveRoutine(Routine(id, args.requiredString("title"), args.optStringOrNull("description"), args.optStringOrNull("schedule"), args.optDoubleOr("confidence", 0.55), MemorySource.valueOf(args.optString("source", "AGENT_INFERENCE")), now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
        register("update_routine", "Update a routine.", objSchema("id" to str(), "confidence" to num(), "schedule" to str(), "title" to str(), required = listOf("id"))) { args ->
            val existing = repo.getRoutine(args.requiredString("id")) ?: throw ToolValidationException("Routine not found")
            repo.saveRoutine(existing.copy(title = args.optStringOrNull("title") ?: existing.title, schedule = args.optStringOrNull("schedule") ?: existing.schedule, confidence = args.optDoubleOr("confidence", existing.confidence), updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }

        register("get_groceries", "List groceries.", objSchema()) { jsonObjectOf("ok" to true, "groceries" to JSONArray(repo.groceries().map { JSONObject(it.toJson()) })) }
        register("add_grocery", "Add a grocery item. Use for statements like 'we're out of rice'.", objSchema("name" to str(), "quantity" to str(), "category" to str(), "reason" to str(), required = listOf("name"))) { args ->
            val now = nowMillis()
            val name = args.requiredString("name")
            val existing = repo.groceries().firstOrNull { it.name.equals(name, true) && it.status == GroceryStatus.ACTIVE }
            val item = (existing ?: GroceryItem(newId(), name, null, null, GroceryStatus.ACTIVE, null, 0.0, null, null, now, now, repo.deviceId, 1, null)).copy(
                quantity = args.optStringOrNull("quantity") ?: existing?.quantity,
                category = args.optStringOrNull("category") ?: existing?.category,
                reason = args.optStringOrNull("reason") ?: existing?.reason,
                status = GroceryStatus.ACTIVE,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
            repo.saveGrocery(item)
            jsonObjectOf("ok" to true, "id" to item.id)
        }
        register("update_grocery", "Update a grocery item.", objSchema("id" to str(), "status" to str(), "quantity" to str(), required = listOf("id"))) { args ->
            val existing = repo.getGrocery(args.requiredString("id")) ?: throw ToolValidationException("Grocery not found")
            repo.saveGrocery(existing.copy(status = parseEnum<GroceryStatus>(args.optStringOrNull("status"), COMMON_DROPPED + mapOf("BOUGHT" to "PURCHASED", "PURCHASED" to "PURCHASED", "ACTIVE" to "ACTIVE")) ?: existing.status, quantity = args.optStringOrNull("quantity") ?: existing.quantity, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }
        register("remove_grocery", "Remove a grocery item.", objSchema("id" to str(), required = listOf("id"))) { args ->
            val existing = repo.getGrocery(args.requiredString("id")) ?: throw ToolValidationException("Grocery not found")
            repo.saveGrocery(existing.copy(status = GroceryStatus.DROPPED, deletedAt = nowMillis(), updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }
        register("clear_purchased_groceries", "Mark active groceries purchased, optionally keeping some. Use for 'I bought everything except eggs'.", objSchema("except" to arr("Names to keep"))) { args ->
            repo.markGroceriesPurchased(args.optStringList("except"))
            jsonObjectOf("ok" to true)
        }

        register("get_important_dates", "List important dates.", objSchema()) { jsonObjectOf("ok" to true, "dates" to JSONArray(repo.importantDates().map { JSONObject(it.toJson()) })) }
        register(
            "create_important_date",
            "Create an important date.",
            objSchema(
                "title" to str(),
                "dateIso" to str("Gregorian or Jalali date; it is normalized to ISO before storage"),
                "relatedPersonId" to str(),
                required = listOf("title", "dateIso"),
            ),
        ) { args ->
            val now = nowMillis()
            val title = args.requiredString("title")
            val dateIso = normalizeDateIso(args.requiredString("dateIso"), nowZoned()) ?: args.requiredString("dateIso")
            val relatedPersonId = args.optStringOrNull("relatedPersonId")
            val existing = repo.importantDates().firstOrNull { date ->
                (relatedPersonId != null && date.relatedPersonId == relatedPersonId) ||
                    date.title.equals(title, ignoreCase = true)
            }
            val item = (existing ?: ImportantDate(
                id = newId(),
                title = title,
                dateIso = dateIso,
                recurrence = null,
                relatedPersonId = relatedPersonId,
                notes = null,
                importance = 0.7,
                createdAt = now,
                updatedAt = now,
                deviceId = repo.deviceId,
                version = 1,
                deletedAt = null,
            )).copy(
                title = title,
                dateIso = dateIso,
                recurrence = args.optStringOrNull("recurrence") ?: existing?.recurrence,
                relatedPersonId = relatedPersonId ?: existing?.relatedPersonId,
                notes = args.optStringOrNull("notes") ?: existing?.notes,
                importance = args.optDoubleOr("importance", existing?.importance ?: 0.7),
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
            repo.saveImportantDate(item)
            jsonObjectOf("ok" to true, "id" to item.id)
        }
        register("update_important_date", "Update an important date.", objSchema("id" to str(), "title" to str(), "dateIso" to str(), required = listOf("id"))) { args ->
            val existing = repo.getImportantDate(args.requiredString("id")) ?: throw ToolValidationException("Date not found")
            repo.saveImportantDate(existing.copy(title = args.optStringOrNull("title") ?: existing.title, dateIso = normalizeDateIso(args.optStringOrNull("dateIso"), nowZoned()) ?: existing.dateIso, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }

        register("get_recurring_responsibilities", "List recurring responsibilities.", objSchema()) { jsonObjectOf("ok" to true, "items" to JSONArray(repo.responsibilities().map { JSONObject(it.toJson()) })) }
        register("create_recurring_responsibility", "Create a recurring responsibility.", objSchema("title" to str(), "cadence" to str(), required = listOf("title", "cadence"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveResponsibility(RecurringResponsibility(id, args.requiredString("title"), args.requiredString("cadence"), parseWhen(args.optStringOrNull("nextDueAt")), null, args.optStringOrNull("notes"), now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
        register("update_recurring_responsibility", "Update a recurring responsibility.", objSchema("id" to str(), "title" to str(), "cadence" to str(), required = listOf("id"))) { args ->
            val existing = repo.getResponsibility(args.requiredString("id")) ?: throw ToolValidationException("Not found")
            repo.saveResponsibility(existing.copy(title = args.optStringOrNull("title") ?: existing.title, cadence = args.optStringOrNull("cadence") ?: existing.cadence, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }

        register("relate_memories", "Create a lightweight relationship between two entities.", objSchema("sourceType" to str(), "sourceId" to str(), "relationshipType" to str(), "targetType" to str(), "targetId" to str(), required = listOf("sourceType", "sourceId", "relationshipType", "targetType", "targetId"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveRelationship(MemoryRelationship(id, args.requiredString("sourceType"), args.requiredString("sourceId"), args.requiredString("relationshipType"), args.requiredString("targetType"), args.requiredString("targetId"), now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
    }

    private fun agentTools() {
        register("get_agent_state", "Read private working notes.", objSchema()) { jsonObjectOf("ok" to true, "state" to JSONArray(repo.agentState().map { JSONObject(it.toJson()) })) }
        register("update_agent_state", "Write a private working note. Fluid and temporary.", objSchema("id" to str(), "kind" to str(), "content" to str(), "confidence" to num(), required = listOf("kind", "content"))) { args ->
            val now = nowMillis()
            val existing = args.optStringOrNull("id")?.let { repo.getAgentState(it) }
            val item = (existing ?: AgentStateEntry(newId(), args.requiredString("kind"), args.requiredString("content"), 0.5, now, now, repo.deviceId, 1, null)).copy(
                kind = args.optStringOrNull("kind") ?: existing?.kind ?: "note",
                content = args.optStringOrNull("content") ?: existing?.content.orEmpty(),
                confidence = args.optDoubleOr("confidence", existing?.confidence ?: 0.5),
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
            repo.saveAgentState(item)
            jsonObjectOf("ok" to true, "id" to item.id)
        }
        register("get_agent_queue", "Read the assistant's private todo queue.", objSchema()) { jsonObjectOf("ok" to true, "queue" to JSONArray(repo.agentQueue().map { JSONObject(it.toJson()) })) }
        register("add_agent_queue_item", "Add a private assistant todo.", objSchema("description" to str(), "priority" to num(), "dueAt" to str(), required = listOf("description"))) { args ->
            val now = nowMillis()
            val id = newId()
            repo.saveAgentQueue(AgentQueueItem(id, args.requiredString("description"), QueueStatus.OPEN, args.optDoubleOr("priority", 0.5), parseWhen(args.optStringOrNull("dueAt")), args.optStringOrNull("relatedEntityType"), args.optStringOrNull("relatedEntityId"), now, now, repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to id)
        }
        register("update_agent_queue_item", "Update a queue item.", objSchema("id" to str(), "status" to str(), "description" to str(), required = listOf("id"))) { args ->
            val existing = repo.getAgentQueue(args.requiredString("id")) ?: throw ToolValidationException("Queue item not found")
            repo.saveAgentQueue(existing.copy(description = args.optStringOrNull("description") ?: existing.description, status = parseEnum<QueueStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED) ?: existing.status, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }
        register("complete_agent_queue_item", "Complete a queue item.", objSchema("id" to str(), required = listOf("id"))) { args ->
            val existing = repo.getAgentQueue(args.requiredString("id")) ?: throw ToolValidationException("Queue item not found")
            repo.saveAgentQueue(existing.copy(status = QueueStatus.DONE, updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true)
        }
        register(
            "update_user_understanding",
            "Create or revise what you know about who this person is. Use this for how they communicate, how they want help, what they are going through, and recurring patterns — not one-off facts (remember) or profile logistics (update_user_profile). If they share how they want help AND what they are going through, write help_style and life_chapter as separate calls. Upsert by id or by facet so each facet has one ACTIVE row. Mark stale rows HISTORICAL instead of deleting. Do not diagnose personality or mental health.",
            objSchema(
                "id" to str("Existing row id from the About them section"),
                "facet" to str("communication, help_style, life_chapter, values, patterns, relationship_to_her, or other"),
                "content" to str("What you understand about them"),
                "confidence" to num("0-1"),
                "importance" to num("0-1"),
                "status" to str("ACTIVE, HISTORICAL, ARCHIVED"),
                "source" to str("USER_EXPLICIT or AGENT_INFERENCE"),
                "sourceMessageId" to str("Origin message id"),
            ),
        ) { args ->
            val now = nowMillis()
            val requestedId = args.optStringOrNull("id")
            val facet = UserUnderstanding.normalizeFacet(args.optStringOrNull("facet"))
            val existing = requestedId?.let { repo.getUserUnderstanding(it) }
                ?: repo.activeUserUnderstandingByFacet(facet)
            val content = args.optStringOrNull("content") ?: existing?.content
                ?: throw ToolValidationException("Missing required field: content")
            val item = (existing ?: UserUnderstanding(
                id = newId(),
                facet = facet,
                content = content,
                confidence = 0.7,
                importance = 0.6,
                source = MemorySource.AGENT_INFERENCE,
                sourceMessageId = null,
                status = MemoryStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                deviceId = repo.deviceId,
                version = 1,
                deletedAt = null,
            )).copy(
                facet = if (args.optStringOrNull("facet") != null) facet else existing?.facet ?: facet,
                content = content,
                confidence = clamp01(args.optDoubleOr("confidence", existing?.confidence ?: 0.7)),
                importance = clamp01(args.optDoubleOr("importance", existing?.importance ?: 0.6)),
                source = parseEnum<MemorySource>(args.optStringOrNull("source")) ?: existing?.source ?: MemorySource.AGENT_INFERENCE,
                sourceMessageId = args.optStringOrNull("sourceMessageId") ?: existing?.sourceMessageId,
                status = parseEnum<MemoryStatus>(args.optStringOrNull("status")) ?: existing?.status ?: MemoryStatus.ACTIVE,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            )
            repo.saveUserUnderstanding(item)
            jsonObjectOf("ok" to true, "id" to item.id, "facet" to item.facet)
        }
        register("update_user_profile", "Update high-value profile fields when the person explicitly shares them.", objSchema("userName" to str(), "assistantName" to str(), "timezone" to str(), "preferredLanguage" to str(), "country" to str(), "typicalWakeTime" to str(), "typicalSleepTime" to str(), "occupationOrStudyContext" to str())) { args ->
            val current = repo.getProfile()
            repo.saveProfile(
                current.copy(
                    userName = args.optStringOrNull("userName") ?: current.userName,
                    assistantName = args.optStringOrNull("assistantName") ?: current.assistantName,
                    timezone = args.optStringOrNull("timezone") ?: current.timezone,
                    preferredLanguage = args.optStringOrNull("preferredLanguage") ?: current.preferredLanguage,
                    country = args.optStringOrNull("country") ?: current.country,
                    typicalWakeTime = args.optStringOrNull("typicalWakeTime") ?: current.typicalWakeTime,
                    typicalSleepTime = args.optStringOrNull("typicalSleepTime") ?: current.typicalSleepTime,
                    occupationOrStudyContext = args.optStringOrNull("occupationOrStudyContext") ?: current.occupationOrStudyContext,
                    updatedAt = nowMillis(),
                    version = current.version + 1,
                ),
            )
            jsonObjectOf("ok" to true)
        }
    }

    private fun calendarTools() {
        register(
            "get_calendar_events",
            "Read internal, device, and Google Calendar events for any date or time slice. Pass from/to for a day, week, month, hour range, or Jalali date. days is only a convenience from the start of from (or today). Context only has the next 7 days — call this for anything else.",
            objSchema(
                "from" to str("Start of the slice: ISO, epoch millis, Jalali, or a phrase such as 'next Tuesday', 'today at 2pm', 'this afternoon'"),
                "to" to str("End of the slice, same formats. A date without a clock includes that whole day."),
                "days" to num("Number of calendar days from from (or today) when to is omitted. Default 7 if from and to are also omitted."),
            ),
        ) { args ->
            val slice = try {
                CalendarWindows.resolveSlice(
                    now = nowZoned(),
                    fromPhrase = args.optStringOrNull("from"),
                    toPhrase = args.optStringOrNull("to"),
                    days = args.optLongOrNull("days"),
                )
            } catch (e: IllegalArgumentException) {
                throw ToolValidationException(e.message ?: "Could not understand the time slice")
            }
            val zone = nowZoned().zone
            val googleConnected = googleCalendar.available()
            val system = systemCalendar.mirror(slice.from, slice.to, excludeGoogleAccounts = googleConnected)
            val google = googleCalendar.mirror(slice.from, slice.to)
            val internal = repo.calendarInRange(slice.from, slice.to).filter { it.source == CalendarSource.INTERNAL }
            jsonObjectOf(
                "ok" to true,
                "from" to slice.from,
                "to" to slice.to,
                "fromWhen" to formatNaturalDate(Instant.ofEpochMilli(slice.from).atZone(zone)),
                "toWhen" to formatNaturalDate(Instant.ofEpochMilli(slice.to).atZone(zone)),
                "calendarPermission" to calendar.hasPermission(),
                "calendarEnabled" to settings.read().calendarEnabled,
                "googleConnected" to googleConnected,
                "internal" to JSONArray(internal.map { calendarJson(it, zone) }),
                "system" to JSONArray(system.map { calendarJson(it, zone) }),
                "google" to JSONArray(google.map { calendarJson(it, zone) }),
            )
        }
        register(
            "create_calendar_event",
            "Create an event. Writes the device calendar when calendar access is on, and always keeps an internal copy.",
            objSchema(
                "title" to str(),
                "when" to str("ISO-8601, epoch millis, Jalali, or a natural phrase such as 'next Tuesday at 10am'"),
                "notes" to str(),
                required = listOf("title", "when"),
            ),
        ) { args ->
            val start = parseWhen(args.requiredString("when")) ?: throw ToolValidationException("Could not understand the time")
            val end = start + HOUR_MS
            val now = nowMillis()
            val id = newId()
            val title = args.requiredString("title")
            val notes = args.optStringOrNull("notes")
            val externalId = systemCalendar.create(title, start, end, notes)
            repo.saveCalendarEvent(
                CalendarEvent(
                    id, title, start, end, null, notes, externalId,
                    if (externalId != null) CalendarSource.SYSTEM else CalendarSource.INTERNAL,
                    null, now, now, repo.deviceId, 1, null,
                ),
            )
            jsonObjectOf("ok" to true, "id" to id, "externalId" to externalId, "systemWrite" to (externalId != null))
        }
        register(
            "update_calendar_event",
            "Move or rename a calendar event. Writes through to the device calendar when access is on. Pass the id from context; do not delete and recreate.",
            objSchema(
                "id" to str(),
                "title" to str(),
                "when" to str("ISO-8601, epoch millis, Jalali, or a natural phrase such as 'tomorrow at 3pm'"),
                "notes" to str(),
                required = listOf("id"),
            ),
        ) { args ->
            val existing = requireCalendarEvent(args.requiredString("id"))
            refuseGoogleWrite(existing)
            val title = args.optStringOrNull("title") ?: existing.title
            val start = args.optStringOrNull("when")?.let {
                parseWhen(it) ?: throw ToolValidationException("Could not understand the time")
            } ?: existing.startAt
            val duration = (existing.endAt ?: (existing.startAt + HOUR_MS)) - existing.startAt
            val end = if (args.optStringOrNull("when") != null) start + duration else existing.endAt
            val notes = args.optStringOrNull("notes") ?: existing.notes
            val pushed = systemCalendar.push(existing, title, start, end, notes)
            repo.saveCalendarEvent(pushed.copy(updatedAt = nowMillis(), version = existing.version + 1))
            jsonObjectOf("ok" to true, "id" to existing.id, "systemWrite" to (pushed.externalId != null && systemCalendar.live()))
        }
        register(
            "delete_calendar_event",
            "Delete a calendar event. Device-calendar deletes need confirmation unless they already said to delete it.",
            objSchema(
                "id" to str(),
                "confirmId" to str("From a previous needsConfirmation result. Do not reuse the event id."),
                "confirmed" to bool("True when they already said to delete it in this message"),
                required = listOf("id"),
            ),
        ) { args ->
            val existing = requireCalendarEvent(args.requiredString("id"))
            refuseGoogleWrite(existing)
            if (existing.source != CalendarSource.INTERNAL && existing.externalId != null) {
                val already = args.optBoolean("confirmed", false)
                val confirmId = args.optStringOrNull("confirmId")
                if (!already && confirmId == null) {
                    val pending = PendingConfirmation(
                        newId(),
                        ConfirmationKind.CALENDAR_DELETE,
                        "Delete external event: ${existing.title}",
                        JSONObject().put("id", existing.id).toString(),
                        nowMillis(),
                    )
                    repo.saveConfirmation(pending)
                    return@register jsonObjectOf(
                        "ok" to false,
                        "needsConfirmation" to true,
                        "confirmId" to pending.id,
                        "summary" to pending.summary,
                    )
                }
                if (!already) {
                    val token = confirmId ?: throw ToolValidationException("Confirmation not found")
                    repo.getConfirmation(token) ?: throw ToolValidationException("Confirmation not found")
                    repo.deleteConfirmation(token)
                }
                systemCalendar.delete(existing)
            }
            repo.deleteCalendarEvent(existing.id)
            jsonObjectOf("ok" to true)
        }
    }

    private suspend fun nowZoned(): ZonedDateTime {
        val profile = repo.getProfile()
        val zone = runCatching { ZoneId.of(profile.timezone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
        return Instant.ofEpochMilli(nowMillis()).atZone(zone)
    }

    private suspend fun calendarWindow(days: Long): Pair<Long, Long> {
        val now = nowZoned()
        val from = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val to = now.toLocalDate().plusDays(days).atStartOfDay(now.zone).toInstant().toEpochMilli()
        return from to to
    }

    private suspend fun requireCalendarEvent(idOrTitle: String): CalendarEvent {
        repo.getCalendarEvent(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
        repo.getCalendarByExternalId(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
        val (from, to) = calendarWindow(400)
        val googleConnected = googleCalendar.available()
        systemCalendar.mirror(from, to, excludeGoogleAccounts = googleConnected)
        googleCalendar.mirror(from, to)
        repo.getCalendarEvent(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
        repo.getCalendarByExternalId(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
        val all = repo.calendarInRange(from, to)
        all.firstOrNull { it.id.equals(idOrTitle, ignoreCase = true) }?.let { return it }
        val exact = all.filter { it.title.equals(idOrTitle, ignoreCase = true) }
        if (exact.size == 1) return exact.first()
        val loose = all.filter { it.title.contains(idOrTitle, ignoreCase = true) }
        return loose.singleOrNull()
            ?: throw ToolValidationException(
                if (exact.isEmpty() && loose.isEmpty()) "Event not found: $idOrTitle"
                else "Multiple events match '$idOrTitle'; pass the id from context",
            )
    }

    private fun calendarJson(event: CalendarEvent, zone: ZoneId): JSONObject {
        val obj = JSONObject(event.toJson())
        obj.put("when", formatNaturalDate(Instant.ofEpochMilli(event.startAt).atZone(zone)))
        event.endAt?.let {
            obj.put(
                "until",
                Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
            )
        }
        return obj
    }

    private fun refuseGoogleWrite(event: CalendarEvent) {
        if (event.source == CalendarSource.GOOGLE) {
            throw ToolValidationException(
                "Google Calendar events are read-only. Change them in Google Calendar.",
            )
        }
    }

    private suspend fun parseWhen(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return it }
        return RelativeTimeParser.parse(raw, nowZoned())?.toInstant()?.toEpochMilli()
    }

    private suspend fun requireTask(idOrTitle: String): TaskItem {
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
}

private const val HOUR_MS = 60 * 60 * 1000L

private fun str(desc: String = "") = "string" to desc
private fun num(desc: String = "") = "number" to desc
private fun bool(desc: String = "") = "boolean" to desc
private fun arr(desc: String = "") = "array" to desc

private fun objSchema(vararg fields: Pair<String, Pair<String, String>>, required: List<String> = emptyList()): String {
    val props = JSONObject()
    fields.forEach { (name, typeDesc) ->
        val schema = JSONObject().put("type", typeDesc.first)
        if (typeDesc.second.isNotBlank()) schema.put("description", typeDesc.second)
        if (typeDesc.first == "array") schema.put("items", JSONObject().put("type", "string"))
        props.put(name, schema)
    }
    return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).toString()
}
