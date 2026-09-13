package com.her.ui.her

import java.text.BreakIterator

/** Reveals streamed text one grapheme at a time so tokens land in place. */
object StreamReveal {
    fun nextEnd(text: String, from: Int): Int {
        if (from >= text.length) return text.length
        val breaker = BreakIterator.getCharacterInstance()
        breaker.setText(text)
        val next = breaker.following(from)
        return if (next == BreakIterator.DONE) text.length else next
    }

    fun prefixLength(current: String, target: String): Int {
        val n = minOf(current.length, target.length)
        var i = 0
        while (i < n && current[i] == target[i]) i += 1
        if (i in 1 until n && current[i - 1].isHighSurrogate()) i -= 1
        return i
    }

    fun advance(current: String, target: String, units: Int): String {
        if (target.isEmpty()) return ""
        var end = if (target.startsWith(current)) current.length else prefixLength(current, target)
        repeat(units.coerceAtLeast(1)) {
            if (end >= target.length) return target
            end = nextEnd(target, end)
        }
        return target.substring(0, end)
    }

    fun catchUpUnits(behind: Int): Int = when {
        behind > 160 -> 16
        behind > 48 -> 4
        else -> 1
    }

    fun catchUpDelayMs(behind: Int): Long = if (behind > 48) 10L else 20L
}
