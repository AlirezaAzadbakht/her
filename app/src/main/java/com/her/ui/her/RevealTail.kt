package com.her.ui.her

import java.text.BreakIterator

/** The last few revealed graphemes ink in gradually, newest faintest, so streamed text bleeds in. */
object RevealTail {
    const val Length = 12

    /** [start, end) of one grapheme and how visible it is (0..1). */
    data class Segment(val start: Int, val end: Int, val alpha: Float)

    /**
     * Segments for the last [tail] graphemes of [text], newest first. Offsets are logical,
     * so the ramp follows reading order in right-to-left text too, and never splits a
     * surrogate pair or combining sequence.
     */
    fun segments(text: String, tail: Int = Length): List<Segment> {
        if (tail <= 0 || text.isEmpty()) return emptyList()
        val breaker = BreakIterator.getCharacterInstance()
        breaker.setText(text)
        val out = ArrayList<Segment>(tail)
        var end = text.length
        var fromEnd = 0
        while (end > 0 && fromEnd < tail) {
            val start = breaker.preceding(end).let { if (it == BreakIterator.DONE) 0 else it }
            out += Segment(start, end, (fromEnd + 1f) / (tail + 1f))
            end = start
            fromEnd += 1
        }
        return out
    }
}
