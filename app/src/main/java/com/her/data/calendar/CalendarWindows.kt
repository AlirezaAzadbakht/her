package com.her.data.calendar

import com.her.core.RelativeTimeParser
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

data class CalendarSlice(
    val from: Long,
    val to: Long,
)

object CalendarWindows {
    const val GOOGLE_ACCOUNT_TYPE = "com.google"
    const val GOOGLE_TTL_MS = 2 * 60 * 1000L
    const val MAX_SLICE_DAYS = 400L

    fun overlaps(startAt: Long, endAt: Long?, from: Long, to: Long): Boolean {
        val end = endAt ?: (startAt + 1)
        return startAt < to && end > from
    }

    fun instanceExternalId(eventId: String, begin: Long): String = "$eventId:$begin"

    fun deviceEventId(externalId: String): String = externalId.substringBefore(':')

    fun parseDeviceEventId(externalId: String): Long? = deviceEventId(externalId).toLongOrNull()

    fun resolveSlice(
        now: ZonedDateTime,
        fromPhrase: String? = null,
        toPhrase: String? = null,
        days: Long? = null,
        maxDays: Long = MAX_SLICE_DAYS,
    ): CalendarSlice {
        val fromRaw = fromPhrase?.trim()?.takeIf { it.isNotEmpty() }
        val toRaw = toPhrase?.trim()?.takeIf { it.isNotEmpty() }
        if (toRaw == null && days == null) {
            namedSlice(fromRaw, now)?.let { return clampSlice(it, maxDays) }
        }
        val fromParsed = fromRaw?.let { parseInstant(it, now) { "Could not understand from: $it" } }
        val toParsed = toRaw?.let { parseInstant(it, now) { "Could not understand to: $it" } }
        val fromIsDate = isDatePhrase(fromRaw, fromParsed)
        val toIsDate = isDatePhrase(toRaw, toParsed)
        val from = when {
            fromParsed != null && fromIsDate -> fromParsed.toLocalDate().atStartOfDay(now.zone)
            fromParsed != null -> fromParsed
            else -> now.toLocalDate().atStartOfDay(now.zone)
        }
        val to = when {
            toParsed != null && toIsDate -> toParsed.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            toParsed != null -> toParsed
            days != null -> from.toLocalDate().plusDays(days.coerceIn(1, maxDays)).atStartOfDay(from.zone)
            fromParsed != null && fromIsDate -> from.plusDays(1)
            fromParsed != null -> from.toLocalDate().plusDays(1).atStartOfDay(from.zone)
            else -> now.toLocalDate().plusDays(7).atStartOfDay(now.zone)
        }
        var fromMs = from.toInstant().toEpochMilli()
        var toMs = to.toInstant().toEpochMilli()
        if (toMs <= fromMs) toMs = fromMs + 60 * 60 * 1000L
        return clampSlice(CalendarSlice(fromMs, toMs), maxDays)
    }

    private fun namedSlice(phrase: String?, now: ZonedDateTime): CalendarSlice? {
        val day = now.toLocalDate()
        val zone = now.zone
        return when (phrase?.lowercase()) {
            "this morning" -> CalendarSlice(
                day.atTime(0, 0).atZone(zone).toInstant().toEpochMilli(),
                day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            )
            "this afternoon" -> CalendarSlice(
                day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
            )
            "this evening", "tonight" -> CalendarSlice(
                day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            else -> null
        }
    }

    private fun parseInstant(raw: String, now: ZonedDateTime, error: () -> String): ZonedDateTime {
        raw.toLongOrNull()?.let { n ->
            val millis = if (n > 10_000_000_000L) n else n * 1000
            return Instant.ofEpochMilli(millis).atZone(now.zone)
        }
        return RelativeTimeParser.parse(raw, now) ?: throw IllegalArgumentException(error())
    }

    private fun isDateOnly(at: ZonedDateTime): Boolean = at.toLocalTime() == LocalTime.MIDNIGHT

    private fun isDatePhrase(raw: String?, parsed: ZonedDateTime?): Boolean {
        if (parsed == null) return false
        if (isDateOnly(parsed)) return true
        if (raw.isNullOrBlank()) return false
        if (raw.toLongOrNull() != null) return false
        if (raw.contains('T', ignoreCase = true)) return false
        return !phraseHasClock(raw)
    }

    private fun phraseHasClock(raw: String): Boolean {
        val text = raw.lowercase()
        if (text == "now") return true
        if (Regex("""\bat\b""").containsMatchIn(text)) return true
        if (Regex("""\d{1,2}:\d{2}""").containsMatchIn(text)) return true
        if (Regex("""\b\d{1,2}\s*(am|pm)\b""").containsMatchIn(text)) return true
        if (Regex("""\b(hour|minute)s?\b""").containsMatchIn(text)) return true
        return false
    }

    private fun clampSlice(slice: CalendarSlice, maxDays: Long): CalendarSlice {
        val cap = slice.from + maxDays * 24 * 60 * 60 * 1000L
        return if (slice.to > cap) slice.copy(to = cap) else slice
    }
}
