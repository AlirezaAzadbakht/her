package com.her.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

fun newId(): String = UUID.randomUUID().toString()

fun nowMillis(): Long = System.currentTimeMillis()

interface TimeProvider {
    fun nowMillis(): Long
    fun zoneId(): ZoneId
    fun now(): ZonedDateTime = Instant.ofEpochMilli(nowMillis()).atZone(zoneId())
}

class SystemTimeProvider(
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zoneId(): ZoneId = zoneProvider()
}

fun redactSecrets(text: String): String {
    if (text.isBlank()) return text
    return SECRET_PATTERNS.fold(text) { acc, regex -> regex.replace(acc, "$1***REDACTED***") }
}

private val SECRET_PATTERNS = listOf(
    Regex("""(?i)(api[_-]?key["\s:=]+)[^\s,"'}]+"""),
    Regex("""(?i)(authorization["\s:=]+bearer\s+)[^\s,"'}]+"""),
    Regex("""(?i)(sk-[a-z0-9-]{8,})"""),
)

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val obj = JSONObject()
    pairs.forEach { (k, v) -> obj.put(k, v ?: JSONObject.NULL) }
    return obj
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun JSONObject.requiredString(key: String): String =
    optStringOrNull(key) ?: throw ToolValidationException("Missing required field: $key")

fun JSONObject.optDoubleOr(key: String, default: Double): Double =
    if (!has(key) || isNull(key)) default else optDouble(key, default)

fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

fun JSONObject.optStringList(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val raw = opt(key) ?: return emptyList()
    return when (raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf { s -> s.isNotBlank() } }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

class ToolValidationException(message: String) : IllegalArgumentException(message)

data class QuietHours(
    val startMinutes: Int = 23 * 60 + 30,
    val endMinutes: Int = 8 * 60,
) {
    fun contains(time: LocalTime): Boolean = contains(time.hour * 60 + time.minute)

    fun contains(minuteOfDay: Int): Boolean {
        return if (startMinutes == endMinutes) {
            false
        } else if (startMinutes < endMinutes) {
            minuteOfDay in startMinutes until endMinutes
        } else {
            minuteOfDay >= startMinutes || minuteOfDay < endMinutes
        }
    }

    fun endLocalTime(): LocalTime = LocalTime.of(endMinutes / 60, endMinutes % 60)
}

object RelativeTimeParser {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(phrase: String, now: ZonedDateTime): ZonedDateTime? {
        val text = phrase.trim().lowercase(Locale.US)
        if (text.isBlank()) return null
        return when {
            text == "now" -> now
            text == "today" -> now.toLocalDate().atStartOfDay(now.zone)
            text == "tomorrow" -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            text == "yesterday" -> now.toLocalDate().minusDays(1).atStartOfDay(now.zone)
            text == "this evening" -> now.toLocalDate().atTime(18, 0).atZone(now.zone)
            text == "tonight" -> now.toLocalDate().atTime(20, 0).atZone(now.zone)
            text == "this morning" -> now.toLocalDate().atTime(9, 0).atZone(now.zone)
            text == "this afternoon" -> now.toLocalDate().atTime(14, 0).atZone(now.zone)
            text == "next week" -> now.toLocalDate().plusWeeks(1).atStartOfDay(now.zone)
            text == "last week" -> now.toLocalDate().minusWeeks(1).atStartOfDay(now.zone)
            text == "next month" -> now.toLocalDate().plusMonths(1).atStartOfDay(now.zone)
            text.startsWith("in ") -> parseIn(text.removePrefix("in ").trim(), now)
            text.startsWith("next ") -> parseNextWeekday(text.removePrefix("next ").trim(), now)
            text.matches(Regex("""\d{4}-\d{2}-\d{2}""")) ->
                LocalDate.parse(text, dateFormatter).atStartOfDay(now.zone)
            else -> null
        }
    }

    private fun parseIn(rest: String, now: ZonedDateTime): ZonedDateTime? {
        val parts = rest.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val amount = parseAmount(parts[0]) ?: return null
        return when {
            parts[1].startsWith("day") -> now.plusDays(amount)
            parts[1].startsWith("hour") -> now.plusHours(amount)
            parts[1].startsWith("week") -> now.plusWeeks(amount)
            parts[1].startsWith("month") -> now.plusMonths(amount)
            parts[1].startsWith("minute") -> now.plusMinutes(amount)
            else -> null
        }
    }

    private fun parseNextWeekday(name: String, now: ZonedDateTime): ZonedDateTime? {
        val target = weekday(name) ?: return null
        var date = now.toLocalDate().plusDays(1)
        while (date.dayOfWeek != target) {
            date = date.plusDays(1)
        }
        return date.atStartOfDay(now.zone)
    }

    private fun parseAmount(raw: String): Long? = raw.toLongOrNull() ?: when (raw.lowercase(Locale.US)) {
        "one" -> 1
        "two" -> 2
        "three" -> 3
        "four" -> 4
        "five" -> 5
        "six" -> 6
        "seven" -> 7
        "eight" -> 8
        "nine" -> 9
        "ten" -> 10
        else -> null
    }

    private fun weekday(name: String) = when (name.take(3)) {
        "mon" -> java.time.DayOfWeek.MONDAY
        "tue" -> java.time.DayOfWeek.TUESDAY
        "wed" -> java.time.DayOfWeek.WEDNESDAY
        "thu" -> java.time.DayOfWeek.THURSDAY
        "fri" -> java.time.DayOfWeek.FRIDAY
        "sat" -> java.time.DayOfWeek.SATURDAY
        "sun" -> java.time.DayOfWeek.SUNDAY
        else -> null
    }
}

fun clamp01(value: Double): Double = min(1.0, max(0.0, value))

fun recencyScore(updatedAt: Long, now: Long, halfLifeMs: Long = 14L * 24 * 60 * 60 * 1000): Double {
    if (updatedAt <= 0L) return 0.0
    val age = max(0L, now - updatedAt).toDouble()
    return 1.0 / (1.0 + age / halfLifeMs.toDouble())
}

fun tokenize(query: String): List<String> =
    query.lowercase(Locale.US)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 }

fun lexicalOverlap(query: String, content: String): Double {
    val q = tokenize(query).toSet()
    if (q.isEmpty()) return 0.0
    val c = tokenize(content).toSet()
    if (c.isEmpty()) return 0.0
    return q.count { it in c }.toDouble() / q.size.toDouble()
}

fun formatNaturalDate(zoned: ZonedDateTime): String =
    zoned.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US))
