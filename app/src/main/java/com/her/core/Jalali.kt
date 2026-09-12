package com.her.core

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Jalali (Persian / Shamsi) calendar conversion, plus parsing of the date shapes Persian
 * speakers actually type: `۱۴۰۶/۰۷/۰۱`, `1406-07-01`, `۱۲ اسفند`, `12 Esfand 1405`.
 *
 * The arithmetic is the standard Birashk-corrected leap cycle used by jalaali; it is exact
 * for Jalali years 1178 to 1633, which covers every date this app will ever see.
 */
object JalaliDate {
    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181,
        1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    private data class Cycle(val leap: Int, val gregorianYear: Int, val marchDay: Int)

    private fun cycle(jy: Int): Cycle {
        require(jy >= BREAKS.first() && jy < BREAKS.last()) { "Jalali year out of range: $jy" }
        val gregorianYear = jy + 621
        var leapJ = -14
        var jp = BREAKS.first()
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val next = BREAKS[i]
            jump = next - jp
            if (jy < next) break
            leapJ += (jump / 33) * 8 + (jump % 33) / 4
            jp = next
        }
        var n = jy - jp
        leapJ += (n / 33) * 8 + ((n % 33) + 3) / 4
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1
        val leapG = gregorianYear / 4 - ((gregorianYear / 100 + 1) * 3) / 4 - 150
        val marchDay = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + ((jump + 4) / 33) * 33
        var leap = ((n + 1) % 33 - 1) % 4
        if (leap == -1) leap = 4
        return Cycle(leap, gregorianYear, marchDay)
    }

    /** The Gregorian date of Farvardin 1 (Nowruz) in the given Jalali year. */
    fun newYear(jy: Int): LocalDate = cycle(jy).let { LocalDate.of(it.gregorianYear, 3, it.marchDay) }

    fun isLeapYear(jy: Int): Boolean = cycle(jy).leap == 0

    fun lengthOfMonth(jy: Int, jm: Int): Int = when {
        jm in 1..6 -> 31
        jm in 7..11 -> 30
        jm == 12 -> if (isLeapYear(jy)) 30 else 29
        else -> throw IllegalArgumentException("Jalali month out of range: $jm")
    }

    fun toGregorian(jy: Int, jm: Int, jd: Int): LocalDate {
        require(jm in 1..12) { "Jalali month out of range: $jm" }
        require(jd in 1..lengthOfMonth(jy, jm)) { "Jalali day out of range: $jy-$jm-$jd" }
        val offset = (jm - 1) * 31 - (jm / 7) * (jm - 7) + (jd - 1)
        return newYear(jy).plusDays(offset.toLong())
    }

    /** Returns year, month, day in the Jalali calendar. */
    fun fromGregorian(date: LocalDate): Triple<Int, Int, Int> {
        var jy = date.year - 621
        var start = newYear(jy)
        if (date.isBefore(start)) {
            jy -= 1
            start = newYear(jy)
        }
        val elapsed = ChronoUnit.DAYS.between(start, date).toInt()
        return if (elapsed <= 185) {
            Triple(jy, elapsed / 31 + 1, elapsed % 31 + 1)
        } else {
            val rest = elapsed - 186
            Triple(jy, 7 + rest / 30, rest % 30 + 1)
        }
    }

    fun format(date: LocalDate): String {
        val (jy, jm, jd) = fromGregorian(date)
        return "%04d/%02d/%02d".format(jy, jm, jd)
    }

    private val MONTHS: Map<String, Int> = buildMap {
        val names = listOf(
            listOf("فروردین", "farvardin"),
            listOf("اردیبهشت", "ordibehesht", "ordibehest"),
            listOf("خرداد", "khordad"),
            listOf("تیر", "tir"),
            listOf("مرداد", "mordad", "amordad"),
            listOf("شهریور", "shahrivar", "shahrivor"),
            listOf("مهر", "mehr"),
            listOf("آبان", "ابان", "aban"),
            listOf("آذر", "اذر", "azar"),
            listOf("دی", "dey", "dei"),
            listOf("بهمن", "bahman"),
            listOf("اسفند", "esfand", "isfand"),
        )
        names.forEachIndexed { index, aliases -> aliases.forEach { put(it, index + 1) } }
    }

    private val numericDate = Regex("""^(\d{3,4})[/\-.](\d{1,2})[/\-.](\d{1,2})$""")
    private val dayMonthYear = Regex("""^(\d{1,2})\s+(\S+)(?:\s+(?:ماه\s+)?(\d{3,4}))?$""")
    private val monthDayYear = Regex("""^(\S+)\s+(\d{1,2})(?:\s+(\d{3,4}))?$""")

    /**
     * Parses a Jalali date phrase into its Gregorian equivalent, or null when the text is not
     * a Jalali date. A phrase without a year resolves to its next occurrence from [now].
     */
    fun parse(phrase: String, now: ZonedDateTime): LocalDate? {
        val text = normalizePersianText(phrase)
        if (text.isEmpty()) return null
        numericDate.matchEntire(text)?.let { match ->
            val jy = match.groupValues[1].toInt()
            if (jy !in 1200..1700) return null
            return runCatching {
                toGregorian(jy, match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull()
        }
        namedDate(text)?.let { (jm, jd, jy) -> return resolve(jm, jd, jy, now) }
        return null
    }

    private fun namedDate(text: String): Triple<Int, Int, Int?>? {
        dayMonthYear.matchEntire(text)?.let { match ->
            val month = MONTHS[match.groupValues[2]] ?: return@let
            return Triple(month, match.groupValues[1].toInt(), match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt())
        }
        monthDayYear.matchEntire(text)?.let { match ->
            val month = MONTHS[match.groupValues[1]] ?: return@let
            return Triple(month, match.groupValues[2].toInt(), match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt())
        }
        return null
    }

    private fun resolve(jm: Int, jd: Int, jy: Int?, now: ZonedDateTime): LocalDate? {
        if (jy != null) {
            val year = if (jy < 200) jy + 1400 else jy
            return runCatching { toGregorian(year, jm, jd) }.getOrNull()
        }
        val currentYear = fromGregorian(now.toLocalDate()).first
        val thisYear = runCatching { toGregorian(currentYear, jm, jd) }.getOrNull() ?: return null
        if (!thisYear.isBefore(now.toLocalDate())) return thisYear
        return runCatching { toGregorian(currentYear + 1, jm, jd) }.getOrNull() ?: thisYear
    }
}

private val PERSIAN_DIGIT_RANGE = '\u06F0'..'\u06F9'
private val ARABIC_DIGIT_RANGE = '\u0660'..'\u0669'

/** Converts Persian and Arabic-Indic digits to ASCII so the rest of the parsers can read them. */
fun normalizeDigits(text: String): String {
    if (text.none { it in PERSIAN_DIGIT_RANGE || it in ARABIC_DIGIT_RANGE }) return text
    return text.map { ch ->
        when (ch) {
            in PERSIAN_DIGIT_RANGE -> '0' + (ch - '\u06F0')
            in ARABIC_DIGIT_RANGE -> '0' + (ch - '\u0660')
            else -> ch
        }
    }.joinToString("")
}

/** Digits, Arabic letter variants, and whitespace folded into one comparable form. */
fun normalizePersianText(text: String): String =
    normalizeDigits(text)
        .replace('\u064A', '\u06CC')
        .replace('\u0643', '\u06A9')
        .replace('\u200C', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase(Locale.US)

private val GREGORIAN_MONTHS: Map<String, Int> = buildMap {
    val full = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )
    full.forEachIndexed { index, name ->
        put(name, index + 1)
        put(name.take(3), index + 1)
    }
    put("sept", 9)
}

private val isoDate = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val isoRecurring = Regex("""^--\d{2}-\d{2}$""")
private val bareMonthDay = Regex("""^(\d{1,2})[/\-.](\d{1,2})$""")
private val wordDayMonth = Regex("""^(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?([a-z]+),?(?:\s+(\d{4}))?$""")
private val wordMonthDay = Regex("""^([a-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?,?(?:\s+(\d{4}))?$""")

/**
 * Normalizes a human date into ISO 8601: `yyyy-MM-dd` when a year is known, or `--MM-dd` for a
 * recurring day such as a birthday. Jalali input is converted to its Gregorian equivalent, which
 * always needs a year to be meaningful. Text that is not a date is returned unchanged.
 */
fun normalizeDateIso(raw: String?, now: ZonedDateTime): String? {
    val original = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val text = normalizePersianText(original)
    if (isoDate.matches(text) || isoRecurring.matches(text)) return text
    JalaliDate.parse(text, now)?.let { return it.toString() }
    bareMonthDay.matchEntire(text)?.let { match ->
        val month = match.groupValues[1].toInt()
        val day = match.groupValues[2].toInt()
        if (month in 1..12 && day in 1..31) return "--%02d-%02d".format(month, day)
    }
    gregorianMonthDay(text)?.let { return it }
    return original
}

private fun gregorianMonthDay(text: String): String? {
    fun build(month: Int, day: Int, year: String): String? {
        if (month !in 1..12 || day !in 1..31) return null
        return if (year.isEmpty()) "--%02d-%02d".format(month, day) else "%s-%02d-%02d".format(year, month, day)
    }
    wordDayMonth.matchEntire(text)?.let { match ->
        val month = GREGORIAN_MONTHS[match.groupValues[2]] ?: return@let
        return build(month, match.groupValues[1].toInt(), match.groupValues[3])
    }
    wordMonthDay.matchEntire(text)?.let { match ->
        val month = GREGORIAN_MONTHS[match.groupValues[1]] ?: return@let
        return build(month, match.groupValues[2].toInt(), match.groupValues[3])
    }
    return null
}
