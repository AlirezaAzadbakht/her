package com.her.work

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class DevicePower(
    val percent: Int,
    val charging: Boolean,
) {
    val criticallyLow: Boolean get() = percent <= BackgroundRunPolicy.LOW_BATTERY_PERCENT && !charging
}

enum class RunDecision {
    RUN,
    SKIP,
    DEFER,
}

data class RunVerdict(
    val decision: RunDecision,
    val reason: String,
)

object BackgroundRunPolicy {
    const val LOW_BATTERY_PERCENT = 15
    const val HOURLY_MIN_GAP_MS = 20 * 60 * 1000L
    const val CATCH_UP_GAP_MS = 3 * 60 * 60 * 1000L
    const val SCHEDULE_VERSION = "v2"
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val HOURLY_EVENT_HORIZON_MS = 2 * 60 * 60 * 1000L
    const val AWAY_EMPTY_DAY_DAYS = 3
    const val AWAY_DAYS = 7
    val NIGHTLY_AT: LocalTime = LocalTime.of(3, 0)

    fun decideHourly(
        llmConfigured: Boolean,
        lastSuccessAt: Long,
        nowMs: Long,
        inQuietHours: Boolean,
        hourlyDuringQuietHours: Boolean,
        power: DevicePower,
    ): RunVerdict {
        if (!llmConfigured) return RunVerdict(RunDecision.SKIP, "LLM is not configured.")
        if (lastSuccessAt > 0 && nowMs - lastSuccessAt < HOURLY_MIN_GAP_MS) {
            return RunVerdict(RunDecision.SKIP, "Skipped; ran recently.")
        }
        if (power.criticallyLow) {
            return RunVerdict(RunDecision.SKIP, "Skipped; battery is low.")
        }
        if (inQuietHours && !hourlyDuringQuietHours) {
            return RunVerdict(RunDecision.SKIP, "Skipped; quiet hours.")
        }
        return RunVerdict(RunDecision.RUN, "run")
    }

    fun decideNightly(
        llmConfigured: Boolean,
        alreadyCompletedToday: Boolean,
        power: DevicePower,
    ): RunVerdict {
        if (alreadyCompletedToday) return RunVerdict(RunDecision.SKIP, "Already completed today.")
        if (!llmConfigured) return RunVerdict(RunDecision.SKIP, "LLM is not configured.")
        if (power.criticallyLow) {
            return RunVerdict(RunDecision.DEFER, "Deferred; battery is low.")
        }
        return RunVerdict(RunDecision.RUN, "run")
    }

    fun decideBriefing(
        llmConfigured: Boolean,
        alreadyCompletedToday: Boolean,
        power: DevicePower,
    ): RunVerdict {
        if (alreadyCompletedToday) return RunVerdict(RunDecision.SKIP, "Already generated today.")
        if (!llmConfigured) return RunVerdict(RunDecision.SKIP, "LLM is not configured.")
        if (power.criticallyLow) {
            return RunVerdict(RunDecision.SKIP, "Skipped; battery is low.")
        }
        return RunVerdict(RunDecision.RUN, "run")
    }

    fun decideSync(driveEnabled: Boolean, power: DevicePower): RunVerdict {
        if (!driveEnabled) return RunVerdict(RunDecision.SKIP, "Drive sync is off.")
        if (power.criticallyLow) {
            return RunVerdict(RunDecision.SKIP, "Skipped; battery is low.")
        }
        return RunVerdict(RunDecision.RUN, "run")
    }

    fun isCatchUp(lastSuccessAt: Long, nowMs: Long): Boolean =
        lastSuccessAt > 0 && nowMs - lastSuccessAt > CATCH_UP_GAP_MS

    /** "overdue" or "today" for a deadline worth one nudge, matching the markers the context shows; null otherwise. */
    fun deadlineState(dueAt: Long, now: ZonedDateTime): String? {
        val due = Instant.ofEpochMilli(dueAt).atZone(now.zone)
        return when {
            due.isBefore(now) -> "overdue"
            due.toLocalDate() == now.toLocalDate() -> "today"
            else -> null
        }
    }

    /** Today's or tomorrow's occurrence by month and day, so a birthday stored with its birth year comes up every year. */
    fun upcomingOccurrence(dateIso: String, today: LocalDate): LocalDate? {
        // ponytail: a one-off past date also matches once a year; costs one hourly pass, check recurrence if it matters.
        val date = runCatching { LocalDate.parse(dateIso.take(10)) }.getOrNull() ?: return null
        return listOf(today, today.plusDays(1)).firstOrNull { it.month == date.month && it.dayOfMonth == date.dayOfMonth }
    }

    /** Nothing to consolidate when they have not written since the last nightly pass. */
    fun nightlyHasNews(lastUserMessageAt: Long, lastNightlyRunAt: Long): Boolean =
        lastNightlyRunAt == 0L || lastUserMessageAt > lastNightlyRunAt

    /** Why to skip the briefing for someone who stopped talking to her, or null. Their next message brings it back. */
    fun briefingAwaySkip(lastUserMessageAt: Long, nowMs: Long, dayHasContent: Boolean): String? {
        if (lastUserMessageAt == 0L) return null
        val awayDays = (nowMs - lastUserMessageAt) / DAY_MS
        return when {
            awayDays >= AWAY_DAYS -> "Skipped; away for $awayDays days."
            awayDays >= AWAY_EMPTY_DAY_DAYS && !dayHasContent -> "Skipped; away and nothing is on today."
            else -> null
        }
    }

    fun scheduleFingerprint(briefingEnd: LocalTime): String {
        val end = briefingEnd.format(DateTimeFormatter.ofPattern("HH:mm"))
        val nightly = NIGHTLY_AT.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "hourly:1h|nightly:$nightly|briefing:$end|sync:6h|$SCHEDULE_VERSION"
    }
}
