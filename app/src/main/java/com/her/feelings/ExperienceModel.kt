package com.her.feelings

import com.her.data.db.CompanionRecord
import com.her.domain.Feeling
import org.json.JSONObject

/** Assessments describe evidence, not additional rewards. Legacy events remain readable. */
fun appraisedFeelings(data: JSONObject): Map<Feeling, Double> {
    if (!data.has("goalProgress")) return mapOf(
        runCatching { Feeling.valueOf(data.getString("feeling")) }.getOrDefault(Feeling.CALM) to data.optDouble("intensity", 0.5).coerceIn(0.0, 1.0))
    val progress = data.optDouble("goalProgress").coerceIn(-1.0, 1.0)
    val contribution = data.optDouble("contribution").coerceIn(0.0, 1.0)
    val uncertainty = data.optDouble("uncertainty").coerceIn(0.0, 1.0)
    val surprise = 1.0 - data.optDouble("expectedness").coerceIn(0.0, 1.0)
    return mapOf(
        Feeling.GLAD to progress.coerceAtLeast(0.0) * contribution * (1.0 - uncertainty * 0.5),
        Feeling.CONCERNED to (-progress).coerceAtLeast(0.0) * 0.6,
        Feeling.CURIOUS to uncertainty * 0.5 + surprise * 0.2,
        Feeling.TENDER to if (data.optString("event") == "BENEFIT_REPORTED") 0.25 * contribution else 0.0,
    )
}

/** A repeated report about one outcome does not reset its age or award it again. */
fun distinctEmotionalEvents(rows: List<CompanionRecord>): List<CompanionRecord> = rows
    .filter { it.kind == "EMOTION_EVENT" && it.deletedAt == null && !it.data().optBoolean("stale") }
    .sortedWith(compareBy<CompanionRecord> { it.data().optLong("occurredAt", it.createdAt) }.thenBy { it.id })
    .distinctBy { row -> row.data().let { d ->
        d.optString("experienceId").takeIf { it.isNotBlank() }?.let { "$it:${d.optString("event")}" } ?: row.id
    } }

fun openExperiences(rows: List<CompanionRecord>): List<CompanionRecord> = rows.filter {
    it.kind == "EMOTION_EXPERIENCE" && it.deletedAt == null && !it.data().optBoolean("stale") && it.data().optString("status") == "OPEN"
}.sortedWith(compareByDescending<CompanionRecord> { it.updatedAt }.thenBy { it.id })

/** Expression is a decision for this user message; it never edits internal emotion scores. */
fun emotionalExpression(rows: List<CompanionRecord>, state: EmotionalState, sourceMessageId: String?): JSONObject {
    val expression = rows.filter { it.kind == "EMOTION_EXPRESSION" && it.deletedAt == null && !it.data().optBoolean("stale") &&
        it.data().optString("sourceMessageId") == sourceMessageId }.maxByOrNull { it.updatedAt }?.data()
    val context = expression?.optString("userContext") ?: "UNKNOWN"
    val behavior = when {
        context == "DISTRESSED" -> "LISTEN"
        context == "CORRECTING" -> "CLARIFY"
        (state.purpose["competence"] ?: 0.5) < 0.4 -> "VERIFY"
        context == "CELEBRATING" -> "CELEBRATE"
        context == "EXPLORING" -> "EXPLORE"
        else -> "HELP"
    }
    return JSONObject().put("tone", if (context == "DISTRESSED") "GENTLE" else if (behavior in setOf("CLARIFY", "VERIFY")) "STEADY" else "WARM")
        .put("behavior", behavior).put("announceFeelings", false).put("userContext", context)
}

fun applicableLessons(rows: List<CompanionRecord>, query: String, now: Long): List<CompanionRecord> = rows.filter {
    it.kind == "EMOTION_LESSON" && it.deletedAt == null && !it.data().optBoolean("stale") &&
        com.her.core.lexicalOverlap(query, it.data().optString("situation")) > 0
}.sortedWith(compareByDescending<CompanionRecord> { lessonConfidence(it, rows) * supportLessonScore(it, query, now) }.thenBy { it.id }).take(4)

/** Only latest quoted feedback per application contributes; UNKNOWN and self-review add nothing. */
fun lessonConfidence(lesson: CompanionRecord, rows: List<CompanionRecord>): Double {
    val applications = rows.filter { it.kind == "EMOTION_APPLICATION" && it.deletedAt == null && !it.data().optBoolean("stale") && it.data().optString("lessonId") == lesson.id }
    val outcomes = applications.map { it.data().optString("outcome") }
    val delta = outcomes.count { it == "HELPFUL" } * 0.08 - outcomes.count { it == "UNHELPFUL" } * 0.15
    return (lesson.data().optDouble("confidence", 0.5).coerceIn(0.0, 0.8) + delta).coerceIn(0.1, 0.9)
}

/** Derived from distinct evidence only: reading, resolution and recall never create an event. */
data class ExperienceTransition(
    val experienceId: String,
    val fromEventId: String,
    val toEventId: String,
    val feeling: Feeling,
    val intensity: Double,
    val occurredAt: Long,
) {
    fun toJson() = JSONObject().put("experienceId", experienceId).put("fromEventId", fromEventId)
        .put("toEventId", toEventId).put("feeling", feeling.name).put("intensity", intensity).put("occurredAt", occurredAt)
}

fun experienceTransitions(rows: List<CompanionRecord>): List<ExperienceTransition> {
    val liveIds = rows.filter { it.kind == "EMOTION_EXPERIENCE" && it.deletedAt == null && !it.data().optBoolean("stale") }.map { it.id }.toSet()
    // Reward deduplication stays stricter. For transitions retain the first negative,
    // neutral and positive appraisal per event type; repeated reports cannot refresh them.
    val developments = rows.filter { it.kind == "EMOTION_EVENT" && it.deletedAt == null && !it.data().optBoolean("stale") }
        .sortedWith(compareBy<CompanionRecord> { it.data().optLong("occurredAt", it.createdAt) }.thenBy { it.id })
        .distinctBy { row ->
            val d = row.data()
            val progress = d.optDouble("goalProgress", 0.0)
            val direction = if (progress > 0) "positive" else if (progress < 0) "negative" else "neutral"
            "${d.optString("experienceId")}:${d.optString("event")}:$direction"
        }
    return developments.filter { it.data().optString("experienceId") in liveIds }
        .groupBy { it.data().getString("experienceId") }.flatMap { (id, events) ->
            val appraised = events.filter { it.data().has("goalProgress") }
            val timeCounts = appraised.groupingBy { it.data().optLong("occurredAt", it.createdAt) }.eachCount()
            appraised.zipWithNext().mapNotNull { (before, after) ->
                val a = before.data()
                val b = after.data()
                val time = b.optLong("occurredAt", after.createdAt)
                // Equal timestamps do not establish a temporal order, even with stable ID sorting.
                val previousTime = a.optLong("occurredAt", before.createdAt)
                if (time <= previousTime || timeCounts[time] != 1 || timeCounts[previousTime] != 1) return@mapNotNull null
                val previous = a.optDouble("goalProgress")
                val current = b.optDouble("goalProgress")
                val feeling = when {
                    previous < 0 && current > 0 -> Feeling.RELIEVED
                    previous > 0 && current < 0 -> Feeling.WISTFUL
                    current > previous && current > 0 -> Feeling.HOPEFUL
                    else -> return@mapNotNull null
                }
                val strength = (kotlin.math.abs(current - previous) / 2.0).coerceIn(0.0, 1.0) *
                    minOf(a.optDouble("confidence", 0.5), b.optDouble("confidence", 0.5)).coerceIn(0.0, 1.0) *
                    (1.0 - b.optDouble("uncertainty", 1.0).coerceIn(0.0, 1.0))
                if (strength <= 0) null else ExperienceTransition(id, before.id, after.id, feeling, strength, time)
            }
        }
}

/** Include resolved situations for recall; their affect still decays normally. */
fun situationFeelings(rows: List<CompanionRecord>, now: Long, query: String = ""): List<JSONObject> = rows
    .filter { it.kind == "EMOTION_EXPERIENCE" && it.deletedAt == null && !it.data().optBoolean("stale") }
    .sortedWith(compareByDescending<CompanionRecord> { com.her.core.lexicalOverlap(query, it.data().optString("intention")) }
        .thenByDescending { it.updatedAt }.thenBy { it.id }).map { experience ->
        val scoped = rows.filter { it.id == experience.id || (it.kind == "EMOTION_EVENT" && it.data().optString("experienceId") == experience.id) }
        JSONObject().put("experienceId", experience.id).put("intention", experience.data().optString("intention"))
            .put("status", experience.data().optString("status")).put("state", emotionalState(scoped, now).toJson())
            .put("latestTransition", experienceTransitions(scoped).lastOrNull()?.toJson() ?: JSONObject.NULL)
    }
