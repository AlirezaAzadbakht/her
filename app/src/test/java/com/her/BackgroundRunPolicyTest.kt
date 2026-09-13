package com.her

import com.her.work.BackgroundRunPolicy
import com.her.work.DevicePower
import com.her.work.RunDecision
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRunPolicyTest {
    private val charged = DevicePower(percent = 80, charging = false)
    private val low = DevicePower(percent = 12, charging = false)
    private val lowCharging = DevicePower(percent = 12, charging = true)
    private val now = 1_700_000_000_000L

    @Test
    fun hourlySkipsWhenUnconfigured() {
        val verdict = BackgroundRunPolicy.decideHourly(
            llmConfigured = false,
            lastSuccessAt = 0,
            nowMs = now,
            inQuietHours = false,
            hourlyDuringQuietHours = false,
            power = charged,
        )
        assertEquals(RunDecision.SKIP, verdict.decision)
        assertTrue(verdict.reason.contains("configured"))
    }

    @Test
    fun hourlySkipsWhenRanRecently() {
        val verdict = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = now - 5 * 60 * 1000,
            nowMs = now,
            inQuietHours = false,
            hourlyDuringQuietHours = false,
            power = charged,
        )
        assertEquals(RunDecision.SKIP, verdict.decision)
        assertTrue(verdict.reason.contains("recently"))
    }

    @Test
    fun hourlySkipsOnLowBatteryUnlessCharging() {
        val skipped = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = 0,
            nowMs = now,
            inQuietHours = false,
            hourlyDuringQuietHours = false,
            power = low,
        )
        assertEquals(RunDecision.SKIP, skipped.decision)
        val running = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = 0,
            nowMs = now,
            inQuietHours = false,
            hourlyDuringQuietHours = false,
            power = lowCharging,
        )
        assertEquals(RunDecision.RUN, running.decision)
    }

    @Test
    fun hourlySkipsQuietHoursByDefault() {
        val skipped = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = 0,
            nowMs = now,
            inQuietHours = true,
            hourlyDuringQuietHours = false,
            power = charged,
        )
        assertEquals(RunDecision.SKIP, skipped.decision)
        val allowed = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = 0,
            nowMs = now,
            inQuietHours = true,
            hourlyDuringQuietHours = true,
            power = charged,
        )
        assertEquals(RunDecision.RUN, allowed.decision)
    }

    @Test
    fun hourlyRunsWhenReady() {
        val verdict = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = now - 40 * 60 * 1000,
            nowMs = now,
            inQuietHours = false,
            hourlyDuringQuietHours = false,
            power = charged,
        )
        assertEquals(RunDecision.RUN, verdict.decision)
    }

    @Test
    fun catchUpAfterLongQuietGap() {
        val lastEvening = now - 8 * 60 * 60 * 1000
        val duringQuiet = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = lastEvening,
            nowMs = now,
            inQuietHours = true,
            hourlyDuringQuietHours = false,
            power = charged,
        )
        assertEquals(RunDecision.SKIP, duringQuiet.decision)
        val firstDaytime = BackgroundRunPolicy.decideHourly(
            llmConfigured = true,
            lastSuccessAt = lastEvening,
            nowMs = now,
            inQuietHours = false,
            hourlyDuringQuietHours = false,
            power = charged,
        )
        assertEquals(RunDecision.RUN, firstDaytime.decision)
        assertTrue(BackgroundRunPolicy.isCatchUp(lastEvening, now))
        assertFalse(BackgroundRunPolicy.isCatchUp(now - 30 * 60 * 1000, now))
        assertFalse(BackgroundRunPolicy.isCatchUp(0, now))
    }

    @Test
    fun nightlyDefersWhenBatteryLow() {
        val deferred = BackgroundRunPolicy.decideNightly(
            llmConfigured = true,
            alreadyCompletedToday = false,
            power = low,
        )
        assertEquals(RunDecision.DEFER, deferred.decision)
        val charging = BackgroundRunPolicy.decideNightly(
            llmConfigured = true,
            alreadyCompletedToday = false,
            power = lowCharging,
        )
        assertEquals(RunDecision.RUN, charging.decision)
    }

    @Test
    fun nightlySkipsWhenDoneOrUnconfigured() {
        assertEquals(
            RunDecision.SKIP,
            BackgroundRunPolicy.decideNightly(true, alreadyCompletedToday = true, power = charged).decision,
        )
        assertEquals(
            RunDecision.SKIP,
            BackgroundRunPolicy.decideNightly(false, alreadyCompletedToday = false, power = charged).decision,
        )
    }

    @Test
    fun briefingSkipsLowBatteryInsteadOfDeferring() {
        val verdict = BackgroundRunPolicy.decideBriefing(
            llmConfigured = true,
            alreadyCompletedToday = false,
            power = low,
        )
        assertEquals(RunDecision.SKIP, verdict.decision)
    }

    @Test
    fun syncSkipsWhenDriveOffOrBatteryLow() {
        assertEquals(RunDecision.SKIP, BackgroundRunPolicy.decideSync(driveEnabled = false, power = charged).decision)
        assertEquals(RunDecision.SKIP, BackgroundRunPolicy.decideSync(driveEnabled = true, power = low).decision)
        assertEquals(RunDecision.RUN, BackgroundRunPolicy.decideSync(driveEnabled = true, power = charged).decision)
    }

    @Test
    fun scheduleFingerprintTracksBriefingEnd() {
        val morning = BackgroundRunPolicy.scheduleFingerprint(LocalTime.of(8, 0))
        val later = BackgroundRunPolicy.scheduleFingerprint(LocalTime.of(9, 30))
        assertEquals("hourly:1h|nightly:03:00|briefing:08:00|sync:6h|v2", morning)
        assertEquals("hourly:1h|nightly:03:00|briefing:09:30|sync:6h|v2", later)
        assertTrue(morning != later)
    }

    @Test
    fun criticallyLowIgnoresChargingAboveThreshold() {
        assertTrue(DevicePower(15, charging = false).criticallyLow)
        assertFalse(DevicePower(16, charging = false).criticallyLow)
        assertFalse(DevicePower(5, charging = true).criticallyLow)
    }
}
