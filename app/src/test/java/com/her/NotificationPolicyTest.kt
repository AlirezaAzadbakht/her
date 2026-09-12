package com.her

import com.her.core.QuietHours
import com.her.notify.NotifyDecision
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    @Test
    fun quietHoursSuppressNonUrgent() {
        val hours = QuietHours(23 * 60 + 30, 8 * 60)
        val night = ZonedDateTime.of(LocalDate.of(2026, 9, 12), LocalTime.of(1, 0), ZoneId.of("UTC"))
        assertTrue(hours.contains(night.toLocalTime()))
        val decision = NotifyDecision(notify = !hours.contains(night.toLocalTime()), reason = "quiet", key = "x")
        assertFalse(decision.notify)
    }
}
