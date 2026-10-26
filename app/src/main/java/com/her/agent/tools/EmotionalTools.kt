package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.requiredString
import com.her.core.optStringOrNull
import com.her.data.db.CompanionRecord
import com.her.data.db.LearningRecord
import com.her.domain.AgentRunType
import com.her.domain.MessageRole
import com.her.domain.ReminderStatus
import com.her.domain.TaskStatus
import com.her.feelings.emotionalKinds
import com.her.feelings.emotionalState
import com.her.feelings.rankEmotionalRecords
import org.json.JSONArray
import org.json.JSONObject

internal val emotionalToolNames = setOf("get_emotional_state", "record_emotional_experience", "save_emotional_understanding", "save_emotional_reflection") + experienceToolNames

internal fun ToolRegistry.registerEmotionalTools() {
    registerExperienceTools()
    register("get_emotional_state", "Read modeled feelings, purpose, and evidence.", objSchema("offset" to num(), "id" to str(), "query" to str("Ranks support lessons for this situation"))) { a ->
        val rows = repo.companionRecords().filter { it.kind in emotionalKinds }
        val now = nowMillis()
        val offset = a.optInt("offset", 0)
        if (offset < 0) throw ToolValidationException("Invalid offset")
        val requestedId = a.optString("id").trim().takeIf { it.isNotEmpty() }
        val query = a.optString("query").trim()
        val selected = rankEmotionalRecords(rows.filter { requestedId == null || it.id == requestedId }, query, now)
        val state = emotionalState(rows, now, repo.openDueAts())
        jsonObjectOf("state" to state.toJson(), "guidance" to JSONArray(state.guidance()), "records" to JSONArray(selected.drop(offset).take(6).map { JSONObject(it.toJson()) }),
            "nextOffset" to if (offset + 6 < selected.size) offset + 6 else null,
            "situationFeelings" to JSONArray(com.her.feelings.situationFeelings(rows, now, query).filter { requestedId == null || it.optString("experienceId") == requestedId }.drop(offset).take(6)),
            "openExperiences" to JSONArray(com.her.feelings.openExperiences(rows).take(8).map { JSONObject(it.toJson()) }),
            // The nightly review matches lessons against the day, so a lesson tried today is visible to record.
            "applicableLessons" to JSONArray(com.her.feelings.applicableLessons(rows, if (runType == AgentRunType.NIGHTLY) "$query ${recentUserMessages(now).joinToString(" ") { it.content }}" else query, now).map { JSONObject(it.toJson()).put("effectiveConfidence", com.her.feelings.lessonConfidence(it, rows)) }),
            "expression" to com.her.feelings.emotionalExpression(rows, state, repo.recentMessages(12).lastOrNull { it.role == MessageRole.USER }?.id),
            "recentUserMessages" to JSONArray(recentUserMessages(now)
                .map { jsonObjectOf("id" to it.id, "content" to it.content.take(800)) }),
            "completedTasks" to JSONArray(repo.tasks().filter { it.status == TaskStatus.DONE && it.deletedAt == null }.sortedByDescending { it.updatedAt }.take(5)
                .map { jsonObjectOf("id" to it.id, "title" to it.title, "updatedAt" to it.updatedAt) }),
            "firedReminders" to JSONArray(repo.reminders().filter { it.status == ReminderStatus.FIRED && it.deletedAt == null }.sortedByDescending { it.updatedAt }.take(5)
                .map { jsonObjectOf("id" to it.id, "message" to it.message, "firedAt" to it.firedAt) }),
            "learningProgress" to JSONArray(repo.learningRecords().filter { showsLearningProgress(it) }.sortedByDescending { it.updatedAt }.take(5)
                .map { jsonObjectOf("id" to it.id, "kind" to it.kind, "result" to it.data().optString("result"), "status" to it.data().optString("status")) }))
    }
    val common = arrayOf("id" to str("Reuse when correcting"), "expectedVersion" to num(),
        "sourceMessageId" to str("User message id from context"), "evidence" to str("Exact substring of that message"), "confidence" to num("0 to 1"))
    register("record_emotional_experience", "Record the event itself, such as help that worked (BENEFIT_REPORTED), before reflecting.",
        objSchema(*common, "experienceId" to str("Full id from save_emotional_thread, or omit"),
            "goalProgress" to num("-1 to 1"), "contribution" to num("0 to 1; share attributable to Her"), "expectedness" to num("0 to 1"), "uncertainty" to num("0 to 1"), "event" to oneOf("", "HELP_OFFERED", "ACTION_SUCCEEDED", "BENEFIT_REPORTED", "MISUNDERSTANDING", "UNDERSTANDING_IMPROVED", "UNRESOLVED_QUESTION", "USER_SHARED"),
            "actionKind" to oneOf("", "task", "reminder", "learning"), "actionId" to str("Id from completedTasks, firedReminders, or learningProgress"),
            "taskId" to str("Completed task id"), "interpretation" to str(), "feeling" to enumField<com.her.domain.Feeling>(), "intensity" to num("0 to 1"),
            required = listOf("event", "interpretation", "feeling", "intensity", "confidence"))) { a ->
        repo.learningTransaction {
            val event = a.requiredString("event")
            val experience = a.optStringOrNull("experienceId")?.let { requireEmotionalRecord(it, "EMOTION_EXPERIENCE") }
            val source: String
            val occurredAt: Long
            val id: String
            if (event == "ACTION_SUCCEEDED") {
                val verified = verifiedAction(a)
                source = verified.first; occurredAt = verified.second; id = verified.third
            } else {
                val message = emotionalEvidence(a)
                source = message.id; occurredAt = message.createdAt
                val base = "EMOTION_EVENT:$event:$source"
                // One message can touch two situations: without the current version, another situation gets its own event
                // instead of taking over the first one's; with it, the event moves (a correction).
                val existing = repo.getCompanionRecord(base)
                val owner = existing?.data()?.optString("experienceId").orEmpty()
                val own = experience?.let { "$base:${it.id.removePrefix("EMOTION_EXPERIENCE:")}" }
                id = when {
                    own != null && repo.getCompanionRecord(own) != null -> own
                    own != null && owner.isNotEmpty() && owner != experience.id && a.optLong("expectedVersion", -1) != existing!!.version -> own
                    else -> base
                }
            }
            val assessments = listOf("goalProgress", "contribution", "expectedness", "uncertainty")
            if (assessments.any { a.has(it) } && !assessments.all { a.has(it) }) throw ToolValidationException("Provide all four appraisal fields together")
            if (event == "BENEFIT_REPORTED" && a.has("contribution") && a.optDouble("contribution") <= 0.0)
                throw ToolValidationException("A reported benefit of your help needs your contribution above 0; with none it is USER_SHARED")
            val data = JSONObject(a.toString()).put("occurredAt", occurredAt).put("relatedIds", JSONArray(listOfNotNull(source, experience?.id)))
            if (experience == null) data.remove("experienceId") else data.put("experienceId", experience.id)
            saveEmotion(id, "EMOTION_EVENT", data, a)
        }
    }
    register("save_emotional_understanding", "Save what they feel, your reading, and the approach as separate fields; pass expectedVersion when updating.",
        objSchema(*common, "situation" to str(), "userFeeling" to str("Short exact substring, such as sad"), "interpretation" to str(), "supportApproach" to str(), "exceptions" to str(),
            required = listOf("id", "sourceMessageId", "evidence", "confidence", "situation", "userFeeling", "interpretation", "supportApproach", "exceptions"))) { a ->
        repo.learningTransaction {
            val message = emotionalEvidence(a)
            if (!message.content.contains(a.requiredString("userFeeling"))) throw ToolValidationException("userFeeling must quote the source")
            saveEmotion("EMOTION_UNDERSTANDING:${a.requiredString("id").removePrefix("EMOTION_UNDERSTANDING:")}", "EMOTION_UNDERSTANDING", JSONObject(a.toString()), a)
        }
    }
    register("save_emotional_reflection", "Reflect on event ids returned by record_emotional_experience; this sends nothing.",
        objSchema("id" to str(), "expectedVersion" to num(), "eventIds" to arr(), "helped" to str("What helped, or Unknown"), "misunderstood" to str("What was misunderstood, or None observed"), "uncertain" to str("What remains uncertain, or None identified"), "adjustment" to str("Next adjustment, or Continue current approach"),
            required = listOf("id", "eventIds", "helped", "misunderstood", "uncertain", "adjustment"))) { a ->
        repo.learningTransaction {
            val ids = a.getJSONArray("eventIds")
            if (ids.length() !in 1..8) throw ToolValidationException("Cite 1 to 8 events")
            val resolved = org.json.JSONArray()
            for (i in 0 until ids.length()) {
                resolved.put(requireEmotionalRecord(ids.getString(i), "EMOTION_EVENT").id)
            }
            saveEmotion("EMOTION_REFLECTION:${a.requiredString("id").removePrefix("EMOTION_REFLECTION:")}", "EMOTION_REFLECTION", JSONObject(a.toString()).put("relatedIds", resolved).put("eventIds", resolved), a)
        }
    }
}

/** The nightly review links and quotes the whole day; other runs only need the latest few. */
private suspend fun ToolRegistry.recentUserMessages(now: Long): List<com.her.domain.ChatMessage> {
    val nightly = runType == AgentRunType.NIGHTLY
    return repo.recentMessages(if (nightly) 200 else 12)
        .filter { it.role == MessageRole.USER && it.deletedAt == null && (!nightly || it.createdAt >= now - 24 * HOUR_MS) }
        .takeLast(if (nightly) 20 else 3)
}

private suspend fun ToolRegistry.verifiedAction(a: JSONObject): Triple<String, Long, String> {
    val kind = a.optString("actionKind").trim().lowercase().ifBlank { "task" }
    val actionId = a.optString("actionId").ifBlank { a.optString("taskId") }.trim()
    if (actionId.isBlank()) throw ToolValidationException("A completed action is required: copy actionId from completedTasks, firedReminders or learningProgress; a user's own report is USER_SHARED or BENEFIT_REPORTED")
    return when (kind) {
        "task" -> {
            val task = repo.getTask(actionId)?.takeIf { it.deletedAt == null && it.status == TaskStatus.DONE }
                ?: throw ToolValidationException("A completed task is required")
            Triple(task.id, task.updatedAt, "EMOTION_EVENT:ACTION_SUCCEEDED:${task.id}")
        }
        "reminder" -> {
            val reminder = repo.getReminder(actionId)?.takeIf { it.deletedAt == null && it.status == ReminderStatus.FIRED }
                ?: throw ToolValidationException("A fired reminder is required")
            Triple(reminder.id, reminder.firedAt ?: reminder.updatedAt, "EMOTION_EVENT:ACTION_SUCCEEDED:reminder:${reminder.id}")
        }
        "learning" -> {
            val row = repo.getLearningRecord(actionId)?.takeIf { it.deletedAt == null && showsLearningProgress(it) }
                ?: throw ToolValidationException("Recorded learning progress is required")
            Triple(row.id, row.updatedAt, "EMOTION_EVENT:ACTION_SUCCEEDED:learning:${row.id}")
        }
        else -> throw ToolValidationException("actionKind must be task, reminder, or learning")
    }
}

internal fun showsLearningProgress(row: LearningRecord): Boolean = when (row.kind) {
    "ATTEMPT" -> row.data().optString("result") in setOf("CORRECT", "PARTIAL")
    "LESSON" -> row.data().optString("status") in setOf("IN_PROGRESS", "COMPLETED")
    else -> false
}

internal suspend fun ToolRegistry.emotionalEvidence(a: JSONObject): com.her.domain.ChatMessage {
    val message = repo.getMessage(a.requiredString("sourceMessageId"))?.takeIf { it.role == MessageRole.USER && it.deletedAt == null }
        ?: throw ToolValidationException("Existing user message required")
    if (!message.content.contains(a.requiredString("evidence"))) throw ToolValidationException("Evidence must quote the user")
    return message
}

internal suspend fun ToolRegistry.saveEmotion(id: String, kind: String, data: JSONObject, args: JSONObject): JSONObject {
    if (id.length > 240) throw ToolValidationException("ID too long")
    val old = repo.getCompanionRecord(id)
    if (old?.deletedAt != null) throw ToolValidationException("Record was forgotten")
    data.remove("id"); data.remove("expectedVersion")
    val previous = old?.data()
    // Revising an appraisal never refreshes the emotional age of the original experience.
    if (kind == "EMOTION_EVENT" && previous != null) data.put("occurredAt", previous.getLong("occurredAt"))
    val canonical = data.keys().asSequence().sorted().joinToString("|") { "$it=${data.get(it)}" }
    if (previous != null && canonical == previous.keys().asSequence().sorted().joinToString("|") { "$it=${previous.get(it)}" }) return jsonObjectOf("ok" to true, "id" to id, "version" to old.version)
    if (old != null && args.optLong("expectedVersion", -1) != old.version) throw ToolValidationException("Record exists at version ${old.version}; pass expectedVersion ${old.version} to update it")
    val now = nowMillis()
    repo.saveCompanionRecord(CompanionRecord(id, id, kind, data.toString(), old?.createdAt ?: now, now, repo.deviceId, (old?.version ?: 0) + 1))
    if (old != null && kind in setOf("EMOTION_EVENT", "EMOTION_REFLECTION", "EMOTION_LESSON")) {
        val affected = mutableSetOf(id)
        var pending = repo.companionRecords().filter { it.kind in setOf("EMOTION_REFLECTION", "EMOTION_LESSON", "EMOTION_APPLICATION") }
        while (pending.isNotEmpty()) {
            val next = mutableListOf<CompanionRecord>()
            pending.forEach { reflection ->
                val links = reflection.data().optJSONArray("relatedIds")
                if (links != null && (0 until links.length()).any { links.optString(it) in affected }) {
                    repo.saveCompanionRecord(reflection.copy(content = reflection.data().put("stale", true).toString(), updatedAt = now, version = reflection.version + 1))
                    affected.add(reflection.id)
                } else next.add(reflection)
            }
            if (next.size == pending.size) break
            pending = next
        }
    }
    if (repo.getCompanionRecord(id)?.deletedAt != null) throw ToolValidationException("Source was forgotten")
    return jsonObjectOf("ok" to true, "id" to id, "version" to (old?.version ?: 0) + 1)
}
