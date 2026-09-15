package com.her.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
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
    private val englishDates = listOf(
        "EEEE, MMMM d, yyyy",
        "MMMM d, yyyy",
        "MMM d, yyyy",
        "d MMMM yyyy",
        "d MMM yyyy",
    ).map { pattern ->
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.US)
    }
    private val naturalDateTime: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
        .toFormatter(Locale.US)
    private val localDateTimeSpace: DateTimeFormatter = DateTimeFormatterBuilder()
        .append(dateFormatter)
        .appendLiteral(' ')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalEnd()
        .toFormatter()
    private val zoneSuffix = Regex("""\s+([A-Za-z_]+(?:/[A-Za-z0-9_+\-]+)+)$""")
    private val atClock = Regex("""^(.*?)\s+at\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val trailingClock = Regex(
        """^(.*?)\s+(\d{1,2}(?::\d{2})?(?::\d{2})?\s*(?:am|pm)?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val dayPart = Regex("""^(.+?)\s+(morning|afternoon|evening|night)$""")

    /** "10 to 11 am" / "۱۰ تا ۱۱ صبح" keeps the start of the range, and the am/pm that follows it. */
    private val clockRange = Regex("""(\d{1,2}(?::\d{2})?)\s*(?:تا|to|until|–|—)\s*\d{1,2}(?::\d{2})?(\s*(?:am|pm))?$""")

    /** "before next Thursday", "by Friday": a deadline phrase still names the day. */
    private val deadlineWords = listOf("before ", "by ", "until ", "on ")
    private val DAY_PARTS = mapOf(
        "morning" to LocalTime.of(9, 0),
        "afternoon" to LocalTime.of(14, 0),
        "evening" to LocalTime.of(18, 0),
        "night" to LocalTime.of(20, 0),
    )
    private val PERSIAN_DAYS = mapOf("امروز" to 0L, "فردا" to 1L, "پسفردا" to 2L, "دیروز" to -1L)
    private val WEEKDAY_WORDS = setOf(
        "monday", "mon", "tuesday", "tue", "tues", "wednesday", "wed", "thursday", "thu", "thur", "thurs",
        "friday", "fri", "saturday", "sat", "sunday", "sun",
    )

    fun parse(phrase: String, now: ZonedDateTime): ZonedDateTime? {
        val raw = normalizeDigits(phrase).trim()
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { n ->
            val millis = if (n > 10_000_000_000L) n else n * 1000
            return Instant.ofEpochMilli(millis).atZone(now.zone)
        }
        parseIso(raw, now)?.let { return it }
        parseNatural(raw, now)?.let { return it }
        // A trailing zone id ("2026-09-22 10:00 Asia/Tehran") names the clock the rest of the phrase is on.
        zoneSuffix.find(raw)?.let { match ->
            val zone = runCatching { ZoneId.of(match.groupValues[1]) }.getOrNull() ?: return@let
            val rest = raw.substring(0, match.range.first).trim()
            return parse(rest, now.withZoneSameInstant(zone))?.withZoneSameInstant(now.zone)
        }
        val text = rewritePersianClock(raw.lowercase(Locale.US))
            .replace(clockRange, "$1$2")
            .let { phrase -> deadlineWords.fold(phrase) { acc, word -> acc.removePrefix(word) } }
            .trim()
        splitClock(text)?.let { (datePhrase, clockPhrase) ->
            val date = parseBareDate(datePhrase, now) ?: return null
            val clock = parseClock(clockPhrase) ?: return null
            return rollPastWeekday(datePhrase, date.atTime(clock).atZone(now.zone), now)
        }
        dayPart.matchEntire(text)?.let { match ->
            val datePhrase = match.groupValues[1].trim()
            val date = parseBareDate(datePhrase, now)
            val time = DAY_PARTS[match.groupValues[2]]
            if (date != null && time != null) {
                return rollPastWeekday(datePhrase, date.atTime(time).atZone(now.zone), now)
            }
        }
        return parseBareDateTime(text, now)
    }

    /** "Thursday at 9am" said on Thursday afternoon means next week's Thursday, not a time already gone. */
    private fun rollPastWeekday(datePhrase: String, at: ZonedDateTime, now: ZonedDateTime): ZonedDateTime =
        if (bareWeekday(datePhrase) != null && at.isBefore(now)) at.plusWeeks(1) else at

    /** "thursday", "on thursday", "this thursday", "every thursday": the day of the week, or null for anything else. */
    private fun bareWeekday(text: String): java.time.DayOfWeek? {
        val word = text.trim()
            .removePrefix("on ").removePrefix("this ").removePrefix("every ")
            .trim().removeSuffix("s")
        return if (word in WEEKDAY_WORDS) weekday(word) else null
    }

    /** امروز / فردا / پس‌فردا / دیروز as a day offset, with or without the zero-width joiner. */
    private fun persianDayOffset(text: String): Long? =
        PERSIAN_DAYS[text.replace("‌", "").replace(" ", "")]

    private fun upcomingWeekday(target: java.time.DayOfWeek, now: ZonedDateTime): LocalDate {
        var date = now.toLocalDate()
        while (date.dayOfWeek != target) {
            date = date.plusDays(1)
        }
        return date
    }

    private fun parseIso(raw: String, now: ZonedDateTime): ZonedDateTime? {
        runCatching { return OffsetDateTime.parse(raw).atZoneSameInstant(now.zone) }
        runCatching { return ZonedDateTime.parse(raw).withZoneSameInstant(now.zone) }
        runCatching { return Instant.parse(raw).atZone(now.zone) }
        runCatching { return LocalDateTime.parse(raw).atZone(now.zone) }
        runCatching { return LocalDateTime.parse(raw, localDateTimeSpace).atZone(now.zone) }
        if (raw.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            return LocalDate.parse(raw, dateFormatter).atStartOfDay(now.zone)
        }
        return null
    }

    private fun parseNatural(raw: String, now: ZonedDateTime): ZonedDateTime? {
        var text = raw.trim()
        val zoneMatch = zoneSuffix.find(text)
        val zone = zoneMatch?.groupValues?.get(1)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        if (zone != null) {
            text = text.substring(0, zoneMatch.range.first).trim()
        }
        val parsed = runCatching { LocalDateTime.parse(text, naturalDateTime) }.getOrNull() ?: return null
        return parsed.atZone(zone ?: now.zone)
    }

    private fun parseBareDateTime(text: String, now: ZonedDateTime): ZonedDateTime? {
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
                ?.atStartOfDay(now.zone)
            else -> parseBareDate(text, now)?.atStartOfDay(now.zone)
        }
    }

    private fun parseBareDate(text: String, now: ZonedDateTime): LocalDate? {
        val trimmed = text.trim()
        return when {
            trimmed == "today" || trimmed == "now" -> now.toLocalDate()
            trimmed == "tomorrow" -> now.toLocalDate().plusDays(1)
            trimmed == "yesterday" -> now.toLocalDate().minusDays(1)
            persianDayOffset(trimmed) != null -> now.toLocalDate().plusDays(persianDayOffset(trimmed)!!)
            trimmed == "this evening" || trimmed == "tonight" ||
                trimmed == "this morning" || trimmed == "this afternoon" -> now.toLocalDate()
            trimmed == "next week" -> now.toLocalDate().plusWeeks(1)
            trimmed == "last week" -> now.toLocalDate().minusWeeks(1)
            trimmed == "next month" -> now.toLocalDate().plusMonths(1)
            trimmed.startsWith("in ") -> parseIn(trimmed.removePrefix("in ").trim(), now)?.toLocalDate()
            trimmed.startsWith("next ") -> parseNextWeekday(trimmed.removePrefix("next ").trim(), now)
            bareWeekday(trimmed) != null -> upcomingWeekday(bareWeekday(trimmed)!!, now)
            trimmed.matches(Regex("""\d{4}-\d{2}-\d{2}""")) -> LocalDate.parse(trimmed, dateFormatter)
            else -> parseEnglishDate(trimmed) ?: JalaliDate.parse(trimmed, now)
        }
    }

    private fun splitClock(text: String): Pair<String, String>? {
        atClock.matchEntire(text)?.let { match ->
            val date = match.groupValues[1].trim()
            val clock = match.groupValues[2].trim()
            if (date.isNotEmpty() && parseClock(clock) != null) return date to clock
        }
        trailingClock.matchEntire(text)?.let { match ->
            val date = match.groupValues[1].trim()
            val clock = match.groupValues[2].trim()
            if (date.isNotEmpty() && parseClock(clock) != null) return date to clock
        }
        return null
    }

    private fun rewritePersianClock(text: String): String =
        text
            .replace("بعد از ظهر", "pm")
            .replace("بعدازظهر", "pm")
            .replace("صبح", "am")
            .replace("عصر", "pm")
            .replace("شب", "pm")
            .replace("ساعت", "at")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun parseEnglishDate(text: String): LocalDate? {
        englishDates.forEach { formatter ->
            runCatching { return LocalDate.parse(text, formatter) }
        }
        return null
    }

    private fun parseClock(raw: String): LocalTime? {
        val text = raw.trim().lowercase(Locale.US).replace(".", "")
        val match = Regex("""^(\d{1,2})(?::(\d{2}))?(?::(\d{2}))?\s*(am|pm)?$""").matchEntire(text)
            ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val second = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val meridiem = match.groupValues[4]
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
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

    private fun parseNextWeekday(name: String, now: ZonedDateTime): LocalDate? {
        val target = weekday(name.substringBefore(' ').trim()) ?: return null
        var date = now.toLocalDate().plusDays(1)
        while (date.dayOfWeek != target) {
            date = date.plusDays(1)
        }
        return date
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

private val SEARCH_LETTER_VARIANTS = mapOf(
    'ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک', 'ة' to 'ه', 'ۀ' to 'ه', 'أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا',
)

private val SEARCH_STOPWORDS = setOf(
    // English
    "a", "an", "the", "and", "or", "but", "not", "no", "to", "of", "in", "on", "at", "by", "for", "with", "from",
    "about", "into", "is", "am", "are", "was", "were", "be", "been", "do", "does", "did", "have", "has", "had",
    "will", "would", "can", "could", "should", "it", "its", "this", "that", "these", "those", "there", "here",
    "what", "when", "where", "who", "why", "how", "which", "me", "my", "we", "our", "us", "you", "your", "they",
    "them", "their", "he", "she", "his", "her", "him", "so", "just", "any", "some", "all", "if", "then", "than",
    "re", "ll", "ve", "don", "im", "up", "out", "get", "got",
    // Persian
    "و", "در", "به", "از", "که", "را", "با", "این", "ان", "است", "هست", "برای", "من", "تو", "او", "ما", "شما",
    "یک", "هم", "چه", "چی", "کی", "رو", "می", "ها", "تا", "اما", "یا", "هر", "بود", "شد", "کن", "کرد", "بر", "دیگه",
)

/** Folds Arabic letter forms to Persian, drops diacritics, and maps Persian/Arabic digits to ASCII. */
fun normalizeForSearch(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        when {
            ch in 'ً'..'ٟ' || ch == 'ٰ' || ch == 'ـ' -> Unit
            ch in '۰'..'۹' -> out.append('0' + (ch - '۰'))
            ch in '٠'..'٩' -> out.append('0' + (ch - '٠'))
            ch == '‌' || ch == '‍' -> out.append(' ')
            else -> out.append(SEARCH_LETTER_VARIANTS[ch] ?: ch)
        }
    }
    return out.toString().lowercase(Locale.ROOT)
}

/** Searchable words in any script, without stopwords. */
fun tokenize(query: String): List<String> =
    normalizeForSearch(query)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 && it !in SEARCH_STOPWORDS }

/**
 * FTS4 MATCH text for a natural-language query: any word may match, as a prefix.
 * Null when nothing searchable is left, so callers skip the query instead of matching the raw sentence.
 */
fun ftsQuery(query: String, maxTokens: Int = 8): String? {
    val tokens = tokenize(query).distinct().take(maxTokens)
    return if (tokens.isEmpty()) null else tokens.joinToString(" OR ") { "$it*" }
}

fun lexicalOverlap(query: String, content: String): Double {
    val q = tokenize(query).toSet()
    if (q.isEmpty()) return 0.0
    val c = tokenize(content).toSet()
    if (c.isEmpty()) return 0.0
    return q.count { it in c }.toDouble() / q.size.toDouble()
}

fun formatNaturalDate(zoned: ZonedDateTime): String =
    zoned.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US))

inline fun <reified T : Enum<T>> parseEnum(raw: String?, aliases: Map<String, String> = emptyMap()): T? {
    if (raw.isNullOrBlank()) return null
    val key = raw.trim().uppercase(Locale.US).replace('-', '_').replace(' ', '_')
    val mapped = aliases[key] ?: key
    return enumValues<T>().firstOrNull { it.name == mapped }
}

fun normalizeStatusKey(raw: String): String =
    raw.trim().uppercase(Locale.US).replace('-', '_').replace(' ', '_')

val COMMON_DONE = mapOf(
    "COMPLETE" to "DONE",
    "COMPLETED" to "DONE",
    "FINISHED" to "DONE",
    "FINISH" to "DONE",
)

val COMMON_DROPPED = mapOf(
    "CANCELLED" to "DROPPED",
    "CANCELED" to "DROPPED",
    "CANCEL" to "DROPPED",
    "DROP" to "DROPPED",
    "DELETE" to "DROPPED",
    "DELETED" to "DROPPED",
    "REMOVED" to "DROPPED",
    "REMOVE" to "DROPPED",
    "CLOSED" to "DROPPED",
)
