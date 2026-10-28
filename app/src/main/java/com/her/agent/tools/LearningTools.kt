package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.optStringOrNull
import com.her.core.requiredString
import com.her.data.db.LearningRecord
import com.her.domain.MessageRole
import com.her.learning.LearningProgress
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

internal fun ToolRegistry.registerLearningTools() {
    register("get_learning", "Read saved paths, lessons, exercises, and progress.",
        objSchema("pathId" to str(), "recordId" to str(), "offset" to num())) { a ->
        val rows = a.optStringOrNull("recordId")?.let { id -> listOfNotNull(repo.getLearningRecord(id)?.takeIf { it.deletedAt == null }) } ?: a.optStringOrNull("pathId")?.let { repo.learningPathRecords(it) } ?: repo.learningRecords().filter { it.kind == "PATH" }
        val progressRows = if (a.has("recordId") && rows.firstOrNull()?.kind == "LESSON") repo.learningPathRecords(rows.first().pathId) else rows
        val offset = a.optInt("offset", 0)
        if (offset < 0) throw ToolValidationException("offset must be nonnegative")
        val page = mutableListOf<LearningRecord>()
        var pageChars = 0
        for (row in rows.sortedByDescending { it.updatedAt }.drop(offset)) {
            val size = row.toJson().length
            if (page.isNotEmpty() && pageChars + size > 4000) break
            page.add(row)
            pageChars += size
            if (page.size == 10) break
        }
        val response = jsonObjectOf("ok" to true, "records" to JSONArray(page.map { JSONObject(it.toJson()) }),
            "total" to rows.size, "nextOffset" to if (offset + page.size < rows.size) offset + page.size else null,
            "progressTotal" to rows.count { it.kind == "LESSON" },
            "progress" to JSONArray(rows.filter { it.kind == "LESSON" }.take(10).map { lesson ->
                val attempts = progressRows.filter { it.kind == "ATTEMPT" && it.data().optString("lessonId") == lesson.id }
                val review = LearningProgress.review(attempts)
                jsonObjectOf("lessonId" to lesson.id, "completion" to lesson.data().optString("status"), "mastery" to review.mastery,
                    "attempts" to attempts.size, "reviewDueAt" to review.dueAt)
            }), "recentUserMessages" to JSONArray(repo.recentMessages(12).filter { it.role == MessageRole.USER }.takeLast(3)
                .map { jsonObjectOf("id" to it.id, "content" to it.content.take(400)) }))
        // Leave room below the orchestrator's 8k tool-output ceiling. Full lesson progress
        // remains available with recordId even when this overview omits some entries.
        val progress = response.getJSONArray("progress")
        while (response.toString().length > 7500 && progress.length() > 0) progress.remove(progress.length() - 1)
        response.put("progressTruncated", progress.length() < response.getInt("progressTotal"))
        response
    }
    register("save_learning_path", "Create or edit a learning path after they accept a roadmap.",
        objSchema("id" to str(), "expectedVersion" to num(), "title" to str(), "goal" to str(), "level" to str(), "language" to str(),
            "minutesPerDay" to num(), "status" to oneOf("", "ACTIVE", "PAUSED", "ARCHIVED", "COMPLETED"),
            "remindersEnabled" to bool(), "reminderTime" to str(), required = listOf("id"))) { a ->
        repo.learningTransaction {
            val id = learningId("path", a.requiredString("id"))
            val old = repo.getLearningRecord(id)
            checkLearningVersion(old, a)
            val data = old?.data() ?: JSONObject().put("status", "ACTIVE").put("remindersEnabled", false).put("minutesPerDay", 15)
            listOf("title", "goal", "level", "language", "minutesPerDay", "status", "remindersEnabled", "reminderTime").forEach { if (a.has(it)) data.put(it, a.get(it)) }
            data.requiredString("title"); data.requiredString("goal")
            if (data.optInt("minutesPerDay") !in 1..240) throw ToolValidationException("minutesPerDay must be 1..240")
            validValue(data, "status", setOf("ACTIVE", "PAUSED", "ARCHIVED", "COMPLETED"))
            if (data.optBoolean("remindersEnabled")) {
                val time = data.requiredString("reminderTime")
                if (runCatching { LocalTime.parse(time) }.isFailure) throw ToolValidationException("reminderTime must be HH:mm")
            }
            saveLearning(id, id, "PATH", data, old)
        }
    }
    register("save_learning_lesson", "Save one roadmap stage; COMPLETED means covered, not mastered.",
        objSchema("id" to str(), "pathId" to str(), "expectedVersion" to num(), "title" to str(), "outcome" to str(), "order" to num(),
            "content" to str(), "status" to oneOf("", "PLANNED", "IN_PROGRESS", "COMPLETED", "SKIPPED"), required = listOf("id", "pathId"))) { a ->
        repo.learningTransaction {
            val path = requireLearning(a.requiredString("pathId"), "PATH")
            val id = learningId("lesson", a.requiredString("id"))
            val old = repo.getLearningRecord(id)
            checkLearningVersion(old, a)
            if (old != null && old.pathId != path.id) throw ToolValidationException("Lesson belongs to a different path")
            val data = old?.data() ?: JSONObject().put("status", "PLANNED").put("order", 0)
            listOf("title", "outcome", "order", "content", "status").forEach { if (a.has(it)) data.put(it, a.get(it)) }
            data.requiredString("title"); data.requiredString("outcome")
            validValue(data, "status", setOf("PLANNED", "IN_PROGRESS", "COMPLETED", "SKIPPED"))
            if (data.optInt("order", -1) < 0) throw ToolValidationException("order must be nonnegative")
            saveLearning(id, path.id, "LESSON", data, old)
        }
    }
    register("set_learning_exercise", "Save an exercise before asking it, and do not reveal accepted answers first.",
        objSchema("id" to str(), "lessonId" to str(), "prompt" to str(), "mode" to oneOf("", "EXACT", "OPEN"),
            "acceptedAnswers" to arr("Exact acceptable answers, including choice labels if applicable"), "rubric" to str(), required = listOf("id", "lessonId", "prompt", "mode"))) { a ->
        repo.learningTransaction {
            val lesson = requireLearning(a.requiredString("lessonId"), "LESSON")
            requireActivePath(lesson.pathId)
            val id = learningId("exercise", a.requiredString("id"))
            val data = JSONObject().put("lessonId", lesson.id).put("prompt", a.requiredString("prompt")).put("mode", a.requiredString("mode"))
            validValue(data, "mode", setOf("EXACT", "OPEN"))
            if (data.getString("mode") == "EXACT") {
                val answers = a.optJSONArray("acceptedAnswers") ?: throw ToolValidationException("EXACT needs acceptedAnswers")
                if (answers.length() == 0 || (0 until answers.length()).any { answers.optString(it).isBlank() }) throw ToolValidationException("Provide nonblank accepted answers")
                data.put("acceptedAnswers", answers)
            } else data.put("rubric", a.requiredString("rubric"))
            val old = repo.getLearningRecord(id)
            if (old != null) {
                if (old.pathId != lesson.pathId || old.content != data.toString()) throw ToolValidationException("Exercise id already exists; use a new id for changed content")
                jsonObjectOf("ok" to true, "id" to old.id, "version" to old.version)
            } else saveLearning(id, lesson.pathId, "EXERCISE", data, null)
        }
    }
    register("record_learning_answer", "Record one real answer from their message for that exercise.",
        objSchema("exerciseId" to str(), "sourceMessageId" to str(), "answer" to str("Text from the source message"),
            "result" to oneOf("OPEN only", "CORRECT", "PARTIAL", "INCORRECT", "UNCERTAIN"), "feedback" to str(),
            required = listOf("exerciseId", "sourceMessageId", "answer", "feedback"))) { a ->
        repo.learningTransaction {
            val exercise = requireLearning(a.requiredString("exerciseId"), "EXERCISE")
            val source = repo.getMessage(a.requiredString("sourceMessageId")) ?: throw ToolValidationException("User message not found")
            if (source.role != MessageRole.USER || source.deletedAt != null) throw ToolValidationException("Answer must come from a user message")
            if (source.createdAt < exercise.createdAt) throw ToolValidationException("Answer predates this exercise")
            val id = "answer:${exercise.id}:${source.id}"
            val old = repo.getLearningRecord(id)
            if (old != null) return@learningTransaction jsonObjectOf("ok" to true, "id" to id, "duplicate" to true, "answer" to old.data())
            requireActivePath(exercise.pathId)
            val answer = a.requiredString("answer")
            if (!LearningProgress.normalize(source.content).contains(LearningProgress.normalize(answer))) throw ToolValidationException("Answer must be text from the source message")
            val ex = exercise.data()
            val result = if (ex.getString("mode") == "EXACT") {
                val accepted = ex.getJSONArray("acceptedAnswers")
                if ((0 until accepted.length()).any { LearningProgress.normalize(accepted.getString(it)) == LearningProgress.normalize(answer) }) "CORRECT" else "INCORRECT"
            } else a.requiredString("result")
            val data = jsonObjectOf("lessonId" to ex.getString("lessonId"), "exerciseId" to exercise.id,
                "sourceMessageId" to source.id, "answer" to answer, "result" to result, "feedback" to a.requiredString("feedback"))
            validValue(data, "result", setOf("CORRECT", "PARTIAL", "INCORRECT", "UNCERTAIN"))
            val saved = saveLearning(id, exercise.pathId, "ATTEMPT", data, null)
            saved.put("result", result)
        }
    }
    register("send_learning_reminder", "Send an opted-in study nudge; the app enforces timing and once per day.",
        objSchema("pathId" to str(), "content" to str(), required = listOf("pathId", "content"))) { a ->
        val path = requireLearning(a.requiredString("pathId"), "PATH")
        val now = repo.now()
        val id = "study-nudge:${path.id}:${now.toLocalDate()}"
        val data = path.data()
        val due = data.optString("status") == "ACTIVE" && data.optBoolean("remindersEnabled") &&
            !settings.read().quietHours.contains(now.toLocalTime()) &&
            runCatching { now.toLocalTime() >= LocalTime.parse(data.getString("reminderTime")) }.getOrDefault(false)
        val claimed = repo.learningTransaction {
            if (!due || repo.getLearningRecord(id) != null) false else {
                saveLearning(id, path.id, "NUDGE", JSONObject(), null); true
            }
        }
        jsonObjectOf("ok" to true, "queued" to claimed)
    }
}

private fun learningId(prefix: String, raw: String): String {
    if (raw.length > 160 || raw.isBlank()) throw ToolValidationException("Use a short stable id")
    return if (raw.startsWith("$prefix:")) raw else "$prefix:$raw"
}
private fun validValue(data: JSONObject, key: String, allowed: Set<String>) {
    if (data.optString(key) !in allowed) throw ToolValidationException("$key must be one of $allowed")
}
private fun checkLearningVersion(old: LearningRecord?, args: JSONObject) {
    if (old != null && (!args.has("expectedVersion") || args.optLong("expectedVersion") != old.version))
        throw ToolValidationException("Record exists at version ${old.version}; read get_learning and pass expectedVersion before editing")
}
private suspend fun ToolRegistry.requireLearning(id: String, kind: String): LearningRecord =
    repo.getLearningRecord(id)?.takeIf { it.kind == kind && it.deletedAt == null }
        ?: throw ToolValidationException("$kind not found: $id; use the id returned by get_learning")
private suspend fun ToolRegistry.requireActivePath(id: String) {
    if (requireLearning(id, "PATH").data().optString("status") != "ACTIVE") throw ToolValidationException("Resume this learning path before practicing")
}
private suspend fun ToolRegistry.saveLearning(id: String, pathId: String, kind: String, data: JSONObject, old: LearningRecord?): JSONObject {
    if (data.toString().length > 3500) throw ToolValidationException("Learning record too large; split into short lessons")
    val now = nowMillis()
    val row = LearningRecord(id, pathId, kind, data.toString(), old?.createdAt ?: now, now, repo.deviceId, (old?.version ?: 0) + 1)
    if (row.toJson().length > 4000) throw ToolValidationException("Learning record too large; split into short lessons")
    repo.saveLearningRecord(row)
    return jsonObjectOf("ok" to true, "id" to id, "version" to row.version)
}
