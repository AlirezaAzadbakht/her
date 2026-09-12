package com.her

import com.her.core.QuietHours
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    private val hours = QuietHours(startMinutes = 23 * 60 + 30, endMinutes = 8 * 60)

    @Test
    fun overnightWindowCoversLateNight() {
        assertTrue(hours.contains(LocalTime.of(23, 45)))
        assertTrue(hours.contains(LocalTime.of(2, 0)))
        assertTrue(hours.contains(LocalTime.of(7, 59)))
    }

    @Test
    fun overnightWindowAllowsDaytime() {
        assertFalse(hours.contains(LocalTime.of(8, 0)))
        assertFalse(hours.contains(LocalTime.of(12, 0)))
        assertFalse(hours.contains(LocalTime.of(23, 29)))
    }

    @Test
    fun sameStartAndEndNeverQuiet() {
        assertFalse(QuietHours(10, 10).contains(LocalTime.of(10, 0)))
    }
}
