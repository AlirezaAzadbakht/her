package com.her.data.calendar

import android.app.Application
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CalendarWindowsTest {
    @Test
    fun overlapIncludesEventThatStartedBeforeWindow() {
        val from = 1_000L
        val to = 2_000L
        assertTrue(CalendarWindows.overlaps(500, 1_500, from, to))
        assertTrue(CalendarWindows.overlaps(1_000, 1_500, from, to))
        assertFalse(CalendarWindows.overlaps(100, 500, from, to))
        assertFalse(CalendarWindows.overlaps(2_000, 2_500, from, to))
    }

    @Test
    fun instanceIdsRoundTrip() {
        val external = CalendarWindows.instanceExternalId("42", 1_700)
        assertEquals("42:1700", external)
        assertEquals("42", CalendarWindows.deviceEventId(external))
        assertEquals(42L, CalendarWindows.parseDeviceEventId(external))
        assertEquals(42L, CalendarWindows.parseDeviceEventId("42"))
    }

    @Test
    fun resolveSliceAcceptsDayHourAndNamedWindows() {
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 9, 13),
            LocalTime.of(11, 0),
            ZoneId.of("Asia/Tehran"),
        )
        val today = CalendarWindows.resolveSlice(now, fromPhrase = "today")
        assertEquals(now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli(), today.from)
        assertEquals(now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli(), today.to)

        val afternoon = CalendarWindows.resolveSlice(now, fromPhrase = "this afternoon")
        assertEquals(now.toLocalDate().atTime(12, 0).atZone(now.zone).toInstant().toEpochMilli(), afternoon.from)
        assertEquals(now.toLocalDate().atTime(18, 0).atZone(now.zone).toInstant().toEpochMilli(), afternoon.to)

        val hours = CalendarWindows.resolveSlice(now, fromPhrase = "today at 2pm", toPhrase = "today at 5pm")
        assertEquals(now.toLocalDate().atTime(14, 0).atZone(now.zone).toInstant().toEpochMilli(), hours.from)
        assertEquals(now.toLocalDate().atTime(17, 0).atZone(now.zone).toInstant().toEpochMilli(), hours.to)

        val far = CalendarWindows.resolveSlice(now, fromPhrase = "in 14 days")
        val day = now.toLocalDate().plusDays(14)
        assertEquals(day.atStartOfDay(now.zone).toInstant().toEpochMilli(), far.from)
        assertEquals(day.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli(), far.to)

        val week = CalendarWindows.resolveSlice(now, days = 7)
        assertEquals(now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli(), week.from)
        assertEquals(now.toLocalDate().plusDays(7).atStartOfDay(now.zone).toInstant().toEpochMilli(), week.to)
    }
}
