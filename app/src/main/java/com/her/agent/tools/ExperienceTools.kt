package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.requiredString
import com.her.data.db.CompanionRecord
import org.json.JSONArray
import org.json.JSONObject

internal val experienceToolNames = setOf("save_emotional_thread", "save_emotional_expression", "save_support_lesson", "record_support_application")

internal suspend fun ToolRegistry.requireEmotionalRecord(id: String, kind: String): CompanionRecord =
    repo.getCompanionRecord(id)?.takeIf { it.kind == kind && it.deletedAt == null && !it.data().optBoolean("stale") }
        ?: throw ToolValidationException("Unavailable $kind ID: $id. For an experience, first call save_emotional_thread and copy its returned full ID; standalone events may omit experienceId.")

internal fun ToolRegistry.registerExperienceTools() {
    val evidence = arrayOf("sourceMessageId" to str("Actual user message UUID"), "evidence" to str("Exact contiguous user quote"), "expectedVersion" to num())
    register("save_emotional_thread", "Create or revise an ongoing experience. Stable ID connects request, work and feedback. OPEN survives emotional decay; RESOLVED needs quoted resolution evidence. Reopening requires new evidence. Updates require expectedVersion.",
        objSchema(*evidence, "id" to str(), "intention" to str(), "status" to oneOf("", "OPEN", "RESOLVED"), "unresolved" to str("Open question, or None when resolved"),
            required = listOf("id", "intention", "status", "unresolved", "sourceMessageId", "evidence"))) { a ->
        repo.learningTransaction {
            val source = emotionalEvidence(a)
            val id = "EMOTION_EXPERIENCE:${a.requiredString("id").removePrefix("EMOTION_EXPERIENCE:")}"
            val old = repo.getCompanionRecord(id)
            if (old != null) {
                val previousSource = repo.getMessage(old.data().optString("sourceMessageId"))
                if (previousSource != null && source.createdAt < previousSource.createdAt) throw ToolValidationException("Status evidence cannot predate the current experience")
            }
            if (old != null && old.data().optString("status") != a.getString("status") && old.data().optString("sourceMessageId") == source.id)
                throw ToolValidationException("A status change requires fresh user evidence")
            val links = mutableSetOf(source.id)
            old?.data()?.optJSONArray("relatedIds")?.let { array -> (0 until array.length()).forEach { links.add(array.getString(it)) } }
            saveEmotion(id, "EMOTION_EXPERIENCE", JSONObject(a.toString()).put("relatedIds", JSONArray(links.toList())), a)
        }
    }
    register("save_emotional_expression", "Assess the current user's context, separately from internal feelings. The app derives tone and next behavior. Quote evidence; this earns no emotional reward.",
        objSchema(*evidence, "userContext" to oneOf("", "DISTRESSED", "CORRECTING", "CELEBRATING", "EXPLORING", "NEUTRAL"),
            required = listOf("sourceMessageId", "evidence", "userContext"))) { a ->
        repo.learningTransaction {
            val source = emotionalEvidence(a)
            if (repo.recentMessages(12).lastOrNull { it.role == com.her.domain.MessageRole.USER }?.id != source.id)
                throw ToolValidationException("Expression requires the current user message")
            val saved = saveEmotion("EMOTION_EXPRESSION:${source.id}", "EMOTION_EXPRESSION", JSONObject(a.toString()), a)
            val rows = repo.companionRecords()
            saved.put("expression", com.her.feelings.emotionalExpression(rows, com.her.feelings.emotionalState(rows, nowMillis(), repo.openDueAts()), source.id))
        }
    }
    register("save_support_lesson", "Turn a saved reflection into a tentative reusable support lesson. Scope it to a situation, include exceptions. This is an interpretation, not a diagnosis or proof of benefit.",
        objSchema("id" to str(), "expectedVersion" to num(), "reflectionId" to str(), "situation" to str(), "approach" to str(), "exceptions" to str(), "confidence" to num("0..0.8; tentative"),
            required = listOf("id", "reflectionId", "situation", "approach", "exceptions", "confidence"))) { a ->
        repo.learningTransaction {
            val reflection = requireEmotionalRecord(a.requiredString("reflectionId"), "EMOTION_REFLECTION")
            if (a.getDouble("confidence") > 0.8) throw ToolValidationException("A tentative lesson's confidence cannot exceed 0.8")
            saveEmotion("EMOTION_LESSON:${a.requiredString("id").removePrefix("EMOTION_LESSON:")}", "EMOTION_LESSON",
                JSONObject(a.toString()).put("relatedIds", JSONArray(listOf(reflection.id))), a)
        }
    }
    register("record_support_application", "Record actual use of a lesson in an experience with outcome UNKNOWN. Later quoted user feedback can revise it to HELPFUL or UNHELPFUL with expectedVersion. One application per lesson/experience; no reward for self-review.",
        objSchema(*evidence, "lessonId" to str(), "experienceId" to str(), "outcome" to oneOf("", "UNKNOWN", "HELPFUL", "UNHELPFUL"),
            required = listOf("lessonId", "experienceId", "outcome", "sourceMessageId", "evidence"))) { a ->
        repo.learningTransaction {
            val message = emotionalEvidence(a)
            val lesson = requireEmotionalRecord(a.requiredString("lessonId"), "EMOTION_LESSON")
            val experience = requireEmotionalRecord(a.requiredString("experienceId"), "EMOTION_EXPERIENCE")
            val identity = "${lesson.id.length}:${lesson.id}:${experience.id}"
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val id = "EMOTION_APPLICATION:$digest"
            val old = repo.getCompanionRecord(id)
            val outcome = a.requiredString("outcome")
            if (old != null) {
                val previousSource = repo.getMessage(old.data().optString("sourceMessageId"))
                if (previousSource != null && message.createdAt < previousSource.createdAt) throw ToolValidationException("Feedback cannot predate the application")
            }
            if (old == null && outcome != "UNKNOWN") throw ToolValidationException("Record application as UNKNOWN before feedback")
            if (old != null && old.data().optString("outcome") != outcome && old.data().optString("sourceMessageId") == message.id)
                throw ToolValidationException("Feedback requires a later user message")
            val links = mutableSetOf(lesson.id, experience.id, message.id)
            old?.data()?.optJSONArray("relatedIds")?.let { array -> (0 until array.length()).forEach { links.add(array.getString(it)) } }
            saveEmotion(id, "EMOTION_APPLICATION", JSONObject(a.toString()).put("relatedIds", JSONArray(links.toList())), a)
        }
    }
}

/** App-authored execution evidence. User-completed tasks and read tools are intentionally excluded. */
internal suspend fun ToolRegistry.captureEmotionalWork(tool: String, payload: JSONObject) {
    if (!payload.optBoolean("ok", true)) return
    val resourceId = payload.optString("id").takeIf { it.isNotBlank() } ?: return
    val time: Long
    val description: String
    when (tool) {
        "set_reminder" -> {
            val reminder = repo.getReminder(resourceId)?.takeIf { it.deletedAt == null } ?: return
            time = reminder.createdAt
            description = "Saved a requested reminder; delivery and benefit are not yet known"
        }
        "save_work_session" -> {
            val session = repo.getCompanionRecord(resourceId)?.takeIf { it.kind == "SESSION" && it.deletedAt == null && !it.data().optBoolean("stale") } ?: return
            if (session.data().optString("draft").isBlank()) return
            time = session.updatedAt
            description = "Saved a work draft; use and benefit are not yet known"
        }
        else -> return
    }
    repo.learningTransaction {
        val id = "EMOTION_EVENT:WORK_COMPLETED:$resourceId"
        if (repo.getCompanionRecord(id) != null) return@learningTransaction
        val data = jsonObjectOf("event" to "WORK_COMPLETED", "origin" to "APP", "actionId" to resourceId,
            "interpretation" to description, "occurredAt" to time, "confidence" to 1.0,
            "goalProgress" to 0.4, "contribution" to 1.0, "expectedness" to 1.0, "uncertainty" to 0.3,
            "relatedIds" to JSONArray(listOf(resourceId)))
        repo.saveCompanionRecord(CompanionRecord(id, id, "EMOTION_EVENT", data.toString(), time, time, repo.deviceId, 1))
    }
}
