package com.her.learning

import com.her.data.db.LearningRecord
import java.text.Normalizer
import java.util.Locale

/** Derived from independent attempt records, so sync cannot overwrite another device's practice. */
object LearningProgress {
    const val DAY = 86_400_000L
    data class Review(val successes: Int, val dueAt: Long?, val mastery: String)
    fun review(attempts: List<LearningRecord>): Review {
        var streak = 0
        var due: Long? = null
        attempts.sortedWith(compareBy<LearningRecord> { it.createdAt }.thenBy { it.id }).forEach {
            when (it.data().optString("result")) {
                "CORRECT" -> { streak++; due = it.createdAt + listOf(1, 3, 7, 14, 30)[(streak - 1).coerceAtMost(4)] * DAY }
                "INCORRECT", "PARTIAL" -> { streak = 0; due = it.createdAt + DAY }
                // Uncertain grading does not alter mastery or the review clock.
            }
        }
        return Review(streak, due, if (streak >= 3) "PRACTICED" else if (due != null) "LEARNING" else "UNASSESSED")
    }
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
