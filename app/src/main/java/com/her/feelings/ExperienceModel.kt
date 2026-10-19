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
