package com.her

import com.her.core.RelativeTimeParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RelativeTimeParserTest {
    private val now = ZonedDateTime.of(LocalDate.of(2026, 9, 11), LocalTime.of(15, 0), ZoneId.of("UTC"))

    @Test
    fun tomorrowAndInThreeDays() {
        assertEquals(LocalDate.of(2026, 9, 12), RelativeTimeParser.parse("tomorrow", now)?.toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 14), RelativeTimeParser.parse("in three days", now)?.toLocalDate())
    }

    @Test
    fun nextFriday() {
        // 2026-09-11 is Friday, so next Friday is 2026-09-18
        assertEquals(LocalDate.of(2026, 9, 18), RelativeTimeParser.parse("next Friday", now)?.toLocalDate())
    }

    @Test
    fun lastWeekAndNextMonth() {
        assertEquals(LocalDate.of(2026, 9, 4), RelativeTimeParser.parse("last week", now)?.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 11), RelativeTimeParser.parse("next month", now)?.toLocalDate())
    }

    @Test
    fun thisEvening() {
        val parsed = RelativeTimeParser.parse("this evening", now)
        assertNotNull(parsed)
        assertEquals(18, parsed!!.hour)
    }
}
