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

    @Test
    fun isoOffsetDateTime() {
        val tehran = ZonedDateTime.of(LocalDate.of(2026, 9, 13), LocalTime.of(0, 6), ZoneId.of("Asia/Tehran"))
        val parsed = RelativeTimeParser.parse("2026-09-15T10:00:00+03:30", tehran)
        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 9, 15), parsed!!.toLocalDate())
        assertEquals(10, parsed.hour)
        assertEquals(0, parsed.minute)
    }

    @Test
    fun spaceSeparatedDateTime() {
        val parsed = RelativeTimeParser.parse("2026-09-15 10:00", now)
        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 9, 15), parsed!!.toLocalDate())
        assertEquals(10, parsed.hour)
    }

    @Test
    fun naturalContextBundleFormat() {
        val tehran = ZonedDateTime.of(LocalDate.of(2026, 9, 13), LocalTime.of(0, 6), ZoneId.of("Asia/Tehran"))
        val parsed = RelativeTimeParser.parse("Tuesday, September 15, 2026 at 10:00 AM Asia/Tehran", tehran)
        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 9, 15), parsed!!.toLocalDate())
        assertEquals(10, parsed.hour)
        assertEquals(ZoneId.of("Asia/Tehran"), parsed.zone)
    }

    @Test
    fun nextTuesdayAtTen() {
        val tehran = ZonedDateTime.of(LocalDate.of(2026, 9, 13), LocalTime.of(0, 6), ZoneId.of("Asia/Tehran"))
        val parsed = RelativeTimeParser.parse("next Tuesday at 10am", tehran)
        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 9, 15), parsed!!.toLocalDate())
        assertEquals(10, parsed.hour)
        assertEquals(0, parsed.minute)
    }
}
