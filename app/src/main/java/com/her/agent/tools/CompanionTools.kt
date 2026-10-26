package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.optStringOrNull
import com.her.core.requiredString
import com.her.data.db.CompanionRecord
import com.her.domain.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

internal val companionToolNames = setOf("get_life_context", "save_life_thread", "save_thread_development", "save_waiting_on",
    "save_checkin_preferences", "save_event_preparation", "save_work_session", "send_companion_checkin", "dismiss_companion_checkin", "save_decision", "save_action_capacity", "save_responsibility_schedule")

internal fun ToolRegistry.registerCompanionTools() {
    register("get_life_context", "Retrieve situations, waiting-on, preferences, preparation, or unfinished work before editing them.",
        objSchema("id" to str(), "query" to str(), "kind" to oneOf("", "THREAD", "DEVELOPMENT", "WAITING", "PREFERENCES", "PREPARATION", "SESSION", "DECISION", "CAPACITY", "PLAN", "SCHEDULE", "OCCURRENCE"), "offset" to num())) { a ->
        val offset = a.optInt("offset", 0)
        if (offset < 0) throw ToolValidationException("offset must be nonnegative")
        val rows = repo.companionRecords().filter { row ->
            row.kind !in com.her.feelings.emotionalKinds && row.kind !in setOf("NOTICE", "FORGOTTEN", "SUPERSESSION", "DELIVERY") && (a.optStringOrNull("id")?.let { row.id == it || row.parentId == it || row.id == companionRecordId(row.kind, it) } ?: true) &&
                (a.optStringOrNull("kind")?.let { row.kind == it } ?: true) &&
                (a.optStringOrNull("query")?.let { row.content.contains(it, true) } ?: true)
        }.sortedWith(compareByDescending<CompanionRecord> { it.updatedAt }.thenBy { it.id })
        val page = mutableListOf<JSONObject>()
        var size = 0
        for (row in rows.drop(offset)) {
            val raw = row.toJson()
            if (page.isNotEmpty() && size + raw.length > 5000) break
            val result = JSONObject(raw)
            if (row.kind == "SCHEDULE") result.put("nextScheduledAt", repo.getReminder(row.data().optString("reminderId"))?.fireAt ?: com.her.companion.nextScheduleAt(repo, row, nowMillis() - 1) ?: JSONObject.NULL)
            page.add(result); size += result.toString().length
            if (page.size == 8) break
        }
        jsonObjectOf("ok" to true, "records" to JSONArray(page), "total" to rows.size,
            "nextOffset" to if (offset + page.size < rows.size) offset + page.size else null,
            "recentUserMessages" to JSONArray(repo.recentMessages(12).filter { it.role == MessageRole.USER }.takeLast(3)
                .map { jsonObjectOf("id" to it.id, "content" to it.content.take(300)) }))
    }
    val common = arrayOf("id" to str("Reuse on retry"), "expectedVersion" to num(),
        "sourceMessageId" to str(), "evidence" to str("Exact contiguous substring of that message"),
        "basis" to oneOf("", "EXPLICIT", "TENTATIVE"),
        "dismissed" to bool("False only when the user explicitly requests resuming this topic"),
        "relatedIds" to arr("Existing record ids"))
    fun save(name: String, kind: String, description: String, vararg fields: Pair<String, Field>) {
        register(name, "$description Pass expectedVersion on update; null clears an optional field.",
            objSchema(*common, *fields, required = (if (kind == "WAITING" || kind == "SCHEDULE") emptyList() else listOf("id")) + listOf("sourceMessageId", "evidence", "basis"))) { a ->
            repo.learningTransaction {
                val rawId = if (kind == "PREFERENCES") "preferences" else a.optStringOrNull("id") ?: when (kind) {
                    "WAITING" -> {
                        val parentId = a.requiredString("openLoopId")
                        repo.companionRecords().firstOrNull { it.kind == "WAITING" && it.parentId == parentId }?.id ?: parentId
                    }
                    "SCHEDULE" -> {
                        val parent = a.optStringOrNull("responsibilityId")
                        parent?.let { p ->
                            repo.companionRecords().firstOrNull { it.kind == "SCHEDULE" && it.deletedAt == null && it.parentId == p }?.id
                        } ?: parent ?: a.requiredString("id")
                    }
                    else -> a.requiredString("id")
                }
                val id = if (kind == "PREFERENCES") "companion:preferences" else "$kind:${rawId.removePrefix("$kind:")}"
                if (id.length > 180) throw ToolValidationException("ID too long")
                val previous = repo.getCompanionRecord(id)
                if (previous?.deletedAt != null && kind != "PREFERENCES") throw ToolValidationException("Record was forgotten; use a new ID only on a new user request")
                val old = previous?.takeIf { it.deletedAt == null }
                if (old != null && old.kind != kind) throw ToolValidationException("Wrong record kind")
                val source = repo.getMessage(a.requiredString("sourceMessageId"))
                    ?.takeIf { it.role == MessageRole.USER && it.deletedAt == null }
                    ?: throw ToolValidationException("Source must be an existing user message")
                if (previous?.deletedAt != null && source.createdAt <= previous.deletedAt) throw ToolValidationException("New preferences need a user request after forgetting")
                val evidence = if (kind == "WAITING") com.her.companion.delegationEvidence(source.content, a.requiredString("evidence")) else a.requiredString("evidence")
                a.put("evidence", evidence)
                if (!source.content.contains(evidence)) throw ToolValidationException("Evidence must quote the source message")
                if (a.optString("basis") !in setOf("EXPLICIT", "TENTATIVE")) throw ToolValidationException("Invalid basis")
                if (kind == "WAITING") {
                    val phrase = a.optStringOrNull("deadlineText")
                    // Providers often fill unused optional numbers with zero. It means unspecified, not clear.
                    if (phrase == null && old != null && !a.isNull("deadline") && a.optLong("deadline") == 0L && old.data().optLong("deadline") > 0L) {
                        a.remove("deadline"); a.remove("deadlineText")
                    }
                    if (phrase != null) {
                        val deadline = if (source.content.contains(phrase)) {
                            com.her.core.RelativeTimeParser.parse(phrase, java.time.Instant.ofEpochMilli(source.createdAt).atZone(repo.profileZone()))
                                ?.toInstant()?.toEpochMilli() ?: throw ToolValidationException("Cannot parse deadlineText; use a date and time")
                        } else if (old?.data()?.optString("deadlineText") == phrase) old.data().optLong("deadline")
                        else throw ToolValidationException("deadlineText must quote the source message's date/time phrase")
                        a.put("deadline", deadline)
                    }
                }
                // Providers fill unused optional strings with "" or echo the saved value. On a decision update both mean unchanged (null clears), so a reported outcome keeps the original rationale and its quote.
                if (kind == "DECISION" && old != null) listOf("reasons", "alternatives", "constraints", "assumptions", "outcome").forEach { if (a.opt(it) == "" || a.opt(it) == old.data().opt(it)) a.remove(it) }
                val data = old?.data() ?: JSONObject().put("status", when (kind) {
                    "DECISION" -> "EXPLORING"; "WAITING" -> "OPEN"; "SESSION" -> "IN_PROGRESS"; "PREFERENCES" -> "ACTIVE"; else -> "ACTIVE"
                })
                val allowed = (common.map { it.first } + fields.map { it.first }).toSet() - setOf("id", "expectedVersion")
                a.keys().forEach { key ->
                    if (key !in allowed && key !in setOf("id", "expectedVersion")) throw ToolValidationException("Unknown field: $key")
                    if (key in allowed) data.put(key, a.get(key))
                }
                // Attribute individual fields: an explicit status edit must not promote an old inference to fact.
                val fieldSources = data.optJSONObject("fieldSources") ?: JSONObject()
                val sourceQuotes = data.optJSONObject("sourceQuotes") ?: JSONObject()
                val provenanceKey = "${source.id}:${a.getString("basis")}"
                a.keys().forEach { key ->
                    if (key in allowed && key !in setOf("sourceMessageId", "evidence", "basis") &&
                        !(kind == "WAITING" && old != null && old.data().opt(key) == a.opt(key))) fieldSources.put(key, provenanceKey)
                }
                sourceQuotes.put(provenanceKey, jsonObjectOf("sourceMessageId" to source.id, "evidence" to evidence, "sourceDate" to source.createdAt, "basis" to a.getString("basis")))
                val usedSources = fieldSources.keys().asSequence().map { fieldSources.getString(it) }.toSet()
                sourceQuotes.keys().asSequence().toList().filter { it !in usedSources }.forEach { sourceQuotes.remove(it) }
                data.put("stale", false)
                data.put("fieldSources", fieldSources).put("sourceQuotes", sourceQuotes)
                validateCompanion(kind, data)
                if (kind == "WAITING") com.her.companion.validateDelegation(repo, id, data, a, source.content)
                if (kind == "DECISION") for (key in listOf("reasons", "alternatives", "constraints", "assumptions", "outcome")) {
                    val claim = a.optStringOrNull(key)
                    // An unquoted claim never replaces a saved one, so a paraphrased outcome update keeps the original rationale.
                    if (claim != null && !source.content.contains(claim, ignoreCase = true)) old?.data()?.opt(key)?.let { data.put(key, it) } ?: data.remove(key)
                }
                if (a.has("dismissed") && a.getString("basis") != "EXPLICIT") throw ToolValidationException("Resuming check-ins requires explicit evidence")
                if (kind == "CAPACITY") {
                    val deps = data.optJSONArray("dependencies")
                    if (deps != null) for (i in 0 until deps.length()) {
                        val dep = deps.getString(i)
                        if (dep == data.optString("obligationId") || (repo.getTask(dep)?.deletedAt != null) || (repo.getTask(dep) == null && repo.getCommitment(dep) == null)) throw ToolValidationException("Invalid prerequisite: $dep")
                    }
                }
                val links = data.optJSONArray("relatedIds")
                if (links != null) for (i in 0 until links.length()) {
                    if (!linkedRecordExists(links.getString(i))) throw ToolValidationException("Linked record not found: ${links.getString(i)}")
                }
                if (kind == "PREPARATION") {
                    val event = repo.getCalendarEvent(data.requiredString("eventId"))?.takeIf { it.deletedAt == null }
                        ?: throw ToolValidationException("Calendar event not found; refresh calendar")
                    data.put("eventStartAt", event.startAt).put("eventEndAt", event.endAt ?: event.startAt)
                }
                if (old == null) {
                    val duplicate = repo.companionRecords().firstOrNull { row -> row.kind == kind && when (kind) {
                        "WAITING" -> row.parentId == data.optString("openLoopId")
                        "PREPARATION" -> row.parentId == data.optString("eventId")
                        "THREAD" -> row.data().optString("title").equals(data.optString("title"), true)
                        "CAPACITY" -> row.parentId == data.optString("obligationId")
                        "SESSION" -> row.parentId == data.optString("obligationId") && row.data().optString("status") != "COMPLETED"
                        else -> false
                    } }
                    if (duplicate != null) throw ToolValidationException("Use existing record ${duplicate.id} at version ${duplicate.version}")
                }
                // Identical retries are no-ops, including after a lost response.
                if (old != null && old.content == data.toString()) return@learningTransaction jsonObjectOf("ok" to true, "id" to id, "version" to old.version, "created" to false)
                if (old != null && a.optLong("expectedVersion", -1) != old.version) throw ToolValidationException("Read current record and pass expectedVersion=${old.version}")
                val parent = when (kind) {
                    "DEVELOPMENT" -> data.requiredString("threadId")
                    "PREPARATION" -> data.requiredString("eventId")
                    "SESSION", "CAPACITY" -> data.requiredString("obligationId")
                    "SCHEDULE" -> data.requiredString("responsibilityId")
                    "WAITING" -> data.requiredString("openLoopId")
                    else -> id
                }
                when (kind) {
                    "DEVELOPMENT" -> requireCompanion(parent, "THREAD")
                    "WAITING" -> {
                        val loop = repo.getOpenLoop(parent)?.takeIf { it.deletedAt == null } ?: throw ToolValidationException("Existing open loop required")
                        val person = repo.getPerson(data.requiredString("personId"))?.takeIf { it.deletedAt == null }
                            ?: throw ToolValidationException("Existing person required")
                        val status = when (data.getString("status")) { "RESOLVED" -> com.her.domain.OpenLoopStatus.CLOSED; "CANCELLED" -> com.her.domain.OpenLoopStatus.DROPPED; else -> com.her.domain.OpenLoopStatus.OPEN }
                        repo.saveOpenLoop(loop.copy(description = if (data.optBoolean("delegated")) data.requiredString("expectedOutcome") else loop.description, status = status, relatedEntityType = "people", relatedEntityId = person.id, updatedAt = nowMillis(), version = loop.version + 1))
                    }
                    "SESSION", "CAPACITY" -> {
                        val task = repo.getTask(parent)?.takeIf { it.deletedAt == null }
                        val commitment = repo.getCommitment(parent)?.takeIf { it.deletedAt == null }
                        if (task == null && commitment == null) throw ToolValidationException("Link an existing task or commitment")
                    }
                    "SCHEDULE" -> {
                        if (repo.getRoutine(parent)?.deletedAt != null || (repo.getRoutine(parent) == null && repo.getResponsibility(parent) == null && repo.getReminder(parent) == null)) throw ToolValidationException("Existing routine, responsibility or reminder required")
                        val reminderId = data.optString("reminderId")
                        if (reminderId.isNotBlank() && repo.getReminder(reminderId)?.let { it.deletedAt == null && it.trigger == com.her.domain.ReminderTrigger.TIME } != true) throw ToolValidationException("Existing clock reminder required")
                        if (repo.companionRecords().any { it.kind == "SCHEDULE" && it.id != id && (it.parentId == parent || (reminderId.isNotBlank() && it.data().optString("reminderId") == reminderId)) }) throw ToolValidationException("Use the existing schedule")
                        if (data.optString("basis") != "EXPLICIT") throw ToolValidationException("Schedules require user evidence")
                    }
                    "PREFERENCES" -> if (a.getString("basis") != "EXPLICIT") throw ToolValidationException("Preferences require an explicit user request")
                }
                if (data.toString().length > 3000) throw ToolValidationException("Keep records concise; save developments separately")
                if (old != null && old.parentId != parent) throw ToolValidationException("Cannot change parent; create a new record")
                val now = nowMillis()
                val row = CompanionRecord(id, parent, kind, data.toString(), old?.createdAt ?: now, now, repo.deviceId, (previous?.version ?: 0) + 1)
                repo.saveCompanionRecord(row)
                if (kind == "SCHEDULE") com.her.companion.refreshScheduleReminder(repo, row, reminderAlarms)
                jsonObjectOf("ok" to true, "id" to id, "version" to row.version, "created" to (old == null), "externalActionPerformed" to false)
            }
        }
    }
    save("save_life_thread", "THREAD", "Keep track of an ongoing situation they want followed, including an unresolved question. A choice they asked to save or remember is save_decision. Feelings about a situation are tracked automatically; do not use this just to track them.",
        "title" to str(), "summary" to str(), "decisions" to str(), "questions" to str(), "nextSteps" to str(),
        "status" to oneOf("", "ACTIVE", "PAUSED", "CLOSED"), "sensitive" to bool())
    save("save_thread_development", "DEVELOPMENT", "Save one development, and correct a mistaken entry in place.",
        "threadId" to str(), "summary" to str(), "status" to oneOf("", "ACTIVE", "SUPERSEDED"))
    save("save_waiting_on", "WAITING", "Record what another person owes on an existing open loop.",
        "openLoopId" to str("Existing open loop; create it first"), "personId" to str("Existing person id; look them up first"), "expectedOutcome" to str(), "deadline" to num("Epoch millis, or omit"),
        "deadlineText" to str("Date/time phrase quoted from the source; overrides deadline"),
        "followUpAt" to num("Earliest epoch millis, or omit"), "followUpEnabled" to bool(),
        "status" to oneOf("", "OPEN", "RESOLVED", "CANCELLED"),
        "delegated" to bool("True when the work is assigned to them"),
        "priority" to num("0 low, 1 normal, 2 high, 3 critical"),
        "progress" to str("Exact quote, or empty"),
        "blocker" to str("Exact quote, or empty"),
        "decisionNeeded" to str("Exact quote, or empty"),
        "dependencies" to arr("Waiting or open-loop ids that must resolve first"))
    save("save_checkin_preferences", "PREFERENCES", "Change check-in preferences; social reconnection needs an explicit opt-in.",
        "status" to oneOf("", "ACTIVE", "PAUSED", "DISABLED"), "socialEnabled" to bool(), "intervalHours" to num("24..720"),
        "localTime" to str("HH:mm"), "tone" to str(), "topicIds" to arr(), "sensitiveTopicIds" to arr(), "maxPerDay" to num("1..3"), "pauseUntil" to num())
    save("save_event_preparation", "PREPARATION", "Save meeting preparation keyed by the calendar event id.",
        "eventId" to str(), "summary" to str(), "questions" to str(), "promises" to str(), "needed" to str(),
        "status" to oneOf("", "ACTIVE", "CANCELLED", "CAPTURED"), "postEventEnabled" to bool())
    save("save_work_session", "SESSION", "Work on an existing task or commitment in chat. When they say resume, write the postponed part now; do not leave it for later again. This does not send or complete the task.",
        "obligationId" to str(), "summary" to str(), "draft" to str(), "nextStep" to str(), "minutesAvailable" to num("1..240"),
        "energy" to str("Only the user's stated energy"), "status" to oneOf("", "IN_PROGRESS", "PAUSED", "DRAFT", "COMPLETED"))
    save("save_decision", "DECISION", "Only when they explicitly ask to save or remember a decision, including one still being explored. When they report how it turned out, set REVIEWED with the quoted outcome. Keeping track of an ongoing situation is save_life_thread, even if they call it a decision.",
        "title" to str(), "summary" to str(), "alternatives" to str("Exact contiguous source substring, or empty if unknown"), "reasons" to str("One exact contiguous source substring; do not join separate quotes or change punctuation"), "constraints" to str("Exact source substring, or empty"), "assumptions" to str("Exact source substring, or empty"),
        "outcome" to str("Exact source substring, or empty"), "reviewAt" to num(), "reviewCondition" to str(), "status" to oneOf("", "EXPLORING", "DECIDED", "REVIEWED"), "sensitive" to bool())
    save("save_action_capacity", "CAPACITY", "Attach their time and energy estimates to an existing task or commitment.",
        "obligationId" to str(), "summary" to str(), "minutes" to num("1..480 conservative upper estimate"), "energy" to oneOf("", "LOW", "MEDIUM", "HIGH"),
        "priority" to num("0..3"), "dependencies" to arr(), "status" to oneOf("", "ACTIVE"))
    save("save_responsibility_schedule", "SCHEDULE", "Pause with status PAUSED and resume that same record; this creates no new reminder.",
        "responsibilityId" to str("Existing routine, responsibility, or reminder id"), "reminderId" to str(), "summary" to str(), "anchorLocal" to str("Local ISO date-time"), "zone" to str("IANA zone id"),
        "zonePolicy" to oneOf("", "PROFILE", "FIXED"), "repeat" to oneOf("", "DAILY", "WEEKLY", "MONTHLY"), "interval" to num("1..52"),
        "obligationType" to oneOf("", "FIXED", "OPTIONAL"), "status" to oneOf("", "ACTIVE", "PAUSED"), "pauseUntil" to num())
    register("dismiss_companion_checkin", "Stop further nudges on this topic until they ask to resume.",
        objSchema("id" to str(), required = listOf("id"))) { a ->
        repo.learningTransaction {
            val row = requireCompanion(a.requiredString("id"))
            val data = row.data().put("dismissed", true)
            repo.saveCompanionRecord(row.copy(content = data.toString(), updatedAt = nowMillis(), version = row.version + 1))
        }
        jsonObjectOf("ok" to true, "id" to a.requiredString("id"))
    }
    register("send_companion_checkin", "Send a life-thread, waiting-on, social, meeting, or work-session nudge; the app may suppress it.",
        objSchema("id" to str("Topic ID or companion:preferences for social"), "phase" to oneOf("", "CHECKIN", "PREPARE", "OUTCOME"), "content" to str(), required = listOf("id", "phase", "content"))) { a ->
        repo.learningTransaction {
            val row = requireCompanion(a.requiredString("id"))
            val reason = com.her.companion.checkinBlock(repo, settings, row, a.requiredString("phase"))
            if (reason != null) return@learningTransaction jsonObjectOf("ok" to true, "queued" to false, "reason" to reason)
            if (a.requiredString("content").length > 1000) throw ToolValidationException("Keep check-ins brief")
            val key = com.her.companion.noticeId(row, a.getString("phase"), repo.now().toLocalDate().toString())
            val now = nowMillis()
            repo.saveCompanionRecord(CompanionRecord(key, row.id, "NOTICE", jsonObjectOf("phase" to a.getString("phase")).toString(), now, now, repo.deviceId, 1))
            jsonObjectOf("ok" to true, "queued" to true, "key" to key)
        }
    }
}

internal fun companionRecordId(kind: String, raw: String): String = "$kind:${raw.removePrefix("$kind:")}"

private suspend fun ToolRegistry.linkedRecordExists(id: String): Boolean =
    repo.getPerson(id)?.deletedAt == null && repo.getPerson(id) != null ||
        repo.getProject(id)?.deletedAt == null && repo.getProject(id) != null ||
        repo.getGoal(id)?.deletedAt == null && repo.getGoal(id) != null ||
        repo.getTask(id)?.deletedAt == null && repo.getTask(id) != null ||
        repo.getCommitment(id)?.deletedAt == null && repo.getCommitment(id) != null ||
        repo.getOpenLoop(id)?.deletedAt == null && repo.getOpenLoop(id) != null ||
        repo.getLong(id)?.deletedAt == null && repo.getLong(id) != null ||
        repo.getShort(id)?.deletedAt == null && repo.getShort(id) != null ||
        repo.getCompanionRecord(id)?.deletedAt == null && repo.getCompanionRecord(id) != null ||
        repo.getRoutine(id)?.deletedAt == null && repo.getRoutine(id) != null ||
        repo.getResponsibility(id)?.deletedAt == null && repo.getResponsibility(id) != null ||
        repo.getReminder(id)?.deletedAt == null && repo.getReminder(id) != null ||
        repo.getCalendarEvent(id)?.deletedAt == null && repo.getCalendarEvent(id) != null

private suspend fun ToolRegistry.requireCompanion(id: String, kind: String? = null): CompanionRecord =
    repo.getCompanionRecord(id)?.takeIf { it.deletedAt == null && (kind == null || it.kind == kind) }
        ?: throw ToolValidationException("Record not found; retrieve life context first")

private fun validateCompanion(kind: String, data: JSONObject) {
    val statuses = when (kind) {
        "DECISION" -> setOf("EXPLORING", "DECIDED", "REVIEWED")
        "CAPACITY" -> setOf("ACTIVE")
        "SCHEDULE" -> setOf("ACTIVE", "PAUSED")
        "THREAD" -> setOf("ACTIVE", "PAUSED", "CLOSED")
        "DEVELOPMENT" -> setOf("ACTIVE", "SUPERSEDED")
        "WAITING" -> setOf("OPEN", "RESOLVED", "CANCELLED")
        "PREFERENCES" -> setOf("ACTIVE", "PAUSED", "DISABLED")
        "PREPARATION" -> setOf("ACTIVE", "CANCELLED", "CAPTURED")
        else -> setOf("IN_PROGRESS", "PAUSED", "DRAFT", "COMPLETED")
    }
    if (data.optString("status") !in statuses) throw ToolValidationException("Invalid status")
    when (kind) {
        "THREAD" -> { data.requiredString("title"); data.requiredString("summary") }
        "DEVELOPMENT", "SESSION", "PREPARATION", "DECISION", "CAPACITY", "SCHEDULE" -> data.requiredString("summary")
        "WAITING" -> data.requiredString("expectedOutcome")
    }
    if (kind in setOf("WAITING", "SESSION") && data.optString("status") in setOf("RESOLVED", "COMPLETED") && data.optString("basis") != "EXPLICIT")
        throw ToolValidationException("Completion requires explicit user evidence")
    for ((key, range) in listOf("intervalHours" to 24..720, "maxPerDay" to 1..3, "minutesAvailable" to 1..240, "minutes" to 1..480, "priority" to 0..3, "interval" to 1..52)) {
        if (data.has(key) && (data.opt(key) !is Number || data.getDouble(key) != data.getInt(key).toDouble() || data.getInt(key) !in range))
            throw ToolValidationException("Invalid $key")
    }
    for (key in listOf("deadline", "followUpAt", "pauseUntil", "reviewAt")) if (data.has(key) && !data.isNull(key) && (data.opt(key) !is Number || data.getDouble(key) < 0)) throw ToolValidationException("Invalid $key")
    for (key in listOf("socialEnabled", "followUpEnabled", "postEventEnabled", "sensitive", "dismissed", "delegated")) if (data.has(key) && data.opt(key) !is Boolean) throw ToolValidationException("Invalid $key")
    for (key in listOf("relatedIds", "topicIds", "sensitiveTopicIds", "dependencies")) if (data.has(key)) {
        val values = data.optJSONArray(key) ?: throw ToolValidationException("$key must be an array")
        if (values.length() > 30 || (0 until values.length()).any { values.opt(it) !is String || values.getString(it).isBlank() }) throw ToolValidationException("Invalid $key")
    }
    if (kind == "DECISION" && data.optString("status") in setOf("DECIDED", "REVIEWED") && data.optString("basis") != "EXPLICIT") throw ToolValidationException("Final decisions and reported outcomes require explicit evidence")
    if (kind == "SCHEDULE") {
        if (runCatching { java.time.LocalDateTime.parse(data.requiredString("anchorLocal")); java.time.ZoneId.of(data.requiredString("zone")) }.isFailure) throw ToolValidationException("Use valid local ISO date-time and IANA zone")
        if (data.optString("repeat") !in setOf("DAILY", "WEEKLY", "MONTHLY") || data.optString("zonePolicy") !in setOf("PROFILE", "FIXED") || data.optString("obligationType") !in setOf("FIXED", "OPTIONAL")) throw ToolValidationException("Specify recurrence, zone policy and obligation type")
    }
    if (data.has("localTime") && runCatching { LocalTime.parse(data.getString("localTime")) }.isFailure) throw ToolValidationException("Use HH:mm")
}
