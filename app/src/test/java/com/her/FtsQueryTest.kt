package com.her

import com.her.core.ftsQuery
import com.her.core.tokenize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsQueryTest {
    @Test
    fun anyWordMayMatchAsAPrefix() {
        assertEquals("lamp* OR living* OR room*", ftsQuery("What about the lamp for the living room?"))
    }

    @Test
    fun keepsPersianWords() {
        assertEquals("برنج* OR تمام*", ftsQuery("برنج تمام شد"))
    }

    @Test
    fun nullWhenNothingSearchableIsLeft() {
        assertNull(ftsQuery("  ?! "))
        assertNull(ftsQuery("the and of"))
    }

    @Test
    fun capsAndDedupesTokens() {
        assertEquals(8, ftsQuery("alpha bravo charlie delta echo foxtrot golf hotel india juliet")!!.split(" OR ").size)
        assertEquals("rice*", ftsQuery("rice Rice RICE"))
    }

    @Test
    fun normalizesPersianDigitsAndArabicLetters() {
        assertEquals("1403*", ftsQuery("۱۴۰۳"))
        assertEquals(listOf("کیک"), tokenize("كيك"))
    }

    @Test
    fun zeroWidthNonJoinerSplitsWords() {
        assertTrue("خواهم" in tokenize("می‌خواهم"))
    }
}
