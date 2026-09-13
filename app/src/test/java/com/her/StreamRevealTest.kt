package com.her

import com.her.ui.her.StreamReveal
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRevealTest {
    @Test
    fun advancesOneGrapheme() {
        assertEquals("H", StreamReveal.advance("", "Hi", 1))
        assertEquals("Hi", StreamReveal.advance("H", "Hi", 1))
        assertEquals("Hi", StreamReveal.advance("Hi", "Hi", 1))
    }

    @Test
    fun resetsToSharedPrefixThenAdvances() {
        assertEquals(3, StreamReveal.prefixLength("Hello", "Help"))
        assertEquals("Help", StreamReveal.advance("Hello", "Help", 1))
    }

    @Test
    fun catchUpTakesSeveralUnits() {
        assertEquals("Hello", StreamReveal.advance("", "Hello", 5))
        assertEquals(16, StreamReveal.catchUpUnits(200))
        assertEquals(1, StreamReveal.catchUpUnits(3))
    }
}
