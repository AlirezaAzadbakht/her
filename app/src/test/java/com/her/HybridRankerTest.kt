package com.her

import com.her.core.lexicalOverlap
import com.her.core.recencyScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRankerTest {
    @Test
    fun overlapPrefersMatchingContent() {
        val query = "Friday grocery shopping"
        val better = lexicalOverlap(query, "User usually shops for groceries on Friday evenings")
        val worse = lexicalOverlap(query, "User likes orange juice")
        assertTrue(better > worse)
    }

    @Test
    fun persianOverlapCounts() {
        assertTrue(lexicalOverlap("برنج تمام شده", "برنج خانه تمام شده است") > 0.0)
    }

    @Test
    fun arabicLetterFormsMatchPersian() {
        assertEquals(1.0, lexicalOverlap("كيك", "کیک شکلاتی"), 0.0)
    }

    @Test
    fun recencyDecaysWithAge() {
        val now = 1_000_000_000L
        val recent = recencyScore(now - 3_600_000, now)
        val old = recencyScore(now - 40L * 24 * 60 * 60 * 1000, now)
        assertTrue(recent > old)
    }
}
