package com.her

import com.her.core.JalaliDate
import com.her.core.RelativeTimeParser
import com.her.core.normalizeDateIso
import com.her.core.normalizeDigits
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JalaliDateTest {
    private val tehran = ZoneId.of("Asia/Tehran")
    private val now = ZonedDateTime.of(2026, 9, 13, 9, 0, 0, 0, tehran)

    @Test
    fun `nowruz anchors each jalali year`() {
        assertEquals(LocalDate.of(2026, 3, 21), JalaliDate.newYear(1405))
        assertEquals(LocalDate.of(2027, 3, 21), JalaliDate.newYear(1406))
        assertEquals(LocalDate.of(2024, 3, 20), JalaliDate.newYear(1403))
    }

    @Test
    fun `converts jalali dates to gregorian`() {
        assertEquals(LocalDate.of(2027, 9, 23), JalaliDate.toGregorian(1406, 7, 1))
        assertEquals(LocalDate.of(2027, 3, 3), JalaliDate.toGregorian(1405, 12, 12))
        assertEquals(LocalDate.of(2026, 9, 13), JalaliDate.toGregorian(1405, 6, 22))
    }

    @Test
    fun `converts gregorian dates back to jalali`() {
        assertEquals(Triple(1405, 6, 22), JalaliDate.fromGregorian(LocalDate.of(2026, 9, 13)))
        assertEquals(Triple(1406, 7, 1), JalaliDate.fromGregorian(LocalDate.of(2027, 9, 23)))
        assertEquals("1405/06/22", JalaliDate.format(LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `round trips every day of a jalali year`() {
        var date = JalaliDate.newYear(1405)
        for (month in 1..12) {
            for (day in 1..JalaliDate.lengthOfMonth(1405, month)) {
                assertEquals(date, JalaliDate.toGregorian(1405, month, day))
                assertEquals(Triple(1405, month, day), JalaliDate.fromGregorian(date))
                date = date.plusDays(1)
            }
        }
        assertEquals(JalaliDate.newYear(1406), date)
    }

    @Test
    fun `parses numeric and persian digit dates`() {
        assertEquals(LocalDate.of(2027, 9, 23), JalaliDate.parse("1406/07/01", now))
        assertEquals(LocalDate.of(2027, 9, 23), JalaliDate.parse("۱۴۰۶/۰۷/۰۱", now))
        assertEquals(LocalDate.of(2027, 9, 23), JalaliDate.parse("1406-07-01", now))
    }

    @Test
    fun `parses month names and resolves the next occurrence`() {
        assertEquals(LocalDate.of(2027, 3, 3), JalaliDate.parse("۱۲ اسفند", now))
        assertEquals(LocalDate.of(2027, 3, 3), JalaliDate.parse("12 esfand", now))
        assertEquals(LocalDate.of(2026, 9, 19), JalaliDate.parse("۲۸ شهریور", now))
        assertEquals(LocalDate.of(2027, 4, 14), JalaliDate.parse("25 farvardin", now))
    }

    @Test
    fun `ignores gregorian shaped input`() {
        assertNull(JalaliDate.parse("2026-07-01", now))
        assertNull(JalaliDate.parse("2026/07/01", now))
        assertNull(JalaliDate.parse("march 12", now))
        assertNull(JalaliDate.parse("next tuesday", now))
    }

    @Test
    fun `normalizes digits`() {
        assertEquals("1406/07/01", normalizeDigits("۱۴۰۶/۰۷/۰۱"))
        assertEquals("2026-09-13", normalizeDigits("2026-09-13"))
    }

    @Test
    fun `normalizes dates for storage`() {
        assertEquals("2027-03-03", normalizeDateIso("۱۲ اسفند", now))
        assertEquals("2027-09-23", normalizeDateIso("۱۴۰۶/۰۷/۰۱", now))
        assertEquals("--03-12", normalizeDateIso("March 12", now))
        assertEquals("--03-12", normalizeDateIso("12 March", now))
        assertEquals("--03-12", normalizeDateIso("03-12", now))
        assertEquals("1990-03-12", normalizeDateIso("March 12, 1990", now))
        assertEquals("2026-09-13", normalizeDateIso("2026-09-13", now))
        assertEquals("--03-12", normalizeDateIso("--03-12", now))
        assertNull(normalizeDateIso(null, now))
        assertEquals("sometime in spring", normalizeDateIso("sometime in spring", now))
    }

    @Test
    fun `time parser accepts jalali input`() {
        val parsed = RelativeTimeParser.parse("۱۴۰۶/۰۷/۰۱", now)
        assertEquals(LocalDate.of(2027, 9, 23), parsed?.toLocalDate())
        val withClock = RelativeTimeParser.parse("1406/07/01 at 10:00", now)
        assertEquals(LocalDate.of(2027, 9, 23), withClock?.toLocalDate())
        assertEquals(10, withClock?.hour)
        assertTrue(RelativeTimeParser.parse("۱۲ اسفند", now)?.toLocalDate() == LocalDate.of(2027, 3, 3))
        val persianClock = RelativeTimeParser.parse("۱۴۰۶/۰۷/۰۱ ساعت ۱۰ صبح", now)
        assertEquals(LocalDate.of(2027, 9, 23), persianClock?.toLocalDate())
        assertEquals(10, persianClock?.hour)
        val persianDigitsClock = RelativeTimeParser.parse("1406/07/01 ساعت 10 صبح", now)
        assertEquals(10, persianDigitsClock?.hour)
    }
}
