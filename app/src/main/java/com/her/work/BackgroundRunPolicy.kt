package com.her.work

import java.time.LocalTime
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

    fun scheduleFingerprint(briefingEnd: LocalTime): String {
        val end = briefingEnd.format(DateTimeFormatter.ofPattern("HH:mm"))
        val nightly = NIGHTLY_AT.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "hourly:1h|nightly:$nightly|briefing:$end|sync:6h|$SCHEDULE_VERSION"
    }
}
