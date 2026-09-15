package com.her

import com.her.ui.her.RevealTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealTailTest {
    @Test
    fun nothingToInkWhenEmptyOrOff() {
        assertTrue(RevealTail.segments("", 5).isEmpty())
        assertTrue(RevealTail.segments("Hello", 0).isEmpty())
    }

    @Test
    fun newestGraphemeIsFaintestAndOlderOnesBrighten() {
        val segments = RevealTail.segments("Hello", 3)
        assertEquals(listOf(4 to 5, 3 to 4, 2 to 3), segments.map { it.start to it.end })
        assertTrue(segments[0].alpha < segments[1].alpha && segments[1].alpha < segments[2].alpha)
        assertTrue(segments.all { it.alpha < 1f })
    }

    @Test
    fun emojiStaysWhole() {
        val segments = RevealTail.segments("hi😊", 1)
        assertEquals(listOf(2 to 4), segments.map { it.start to it.end })
    }

    @Test
    fun persianFollowsLogicalOrder() {
        val segments = RevealTail.segments("سلام", 2)
        assertEquals(listOf(3 to 4, 2 to 3), segments.map { it.start to it.end })
    }

    @Test
    fun shortTextInksEveryGrapheme() {
        val segments = RevealTail.segments("ok", 12)
        assertEquals(2, segments.size)
        assertEquals(0, segments.last().start)
    }
}
