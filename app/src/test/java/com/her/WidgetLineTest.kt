package com.her

import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.widget.HerWidget
import com.her.widget.WidgetFeeling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetLineTest {
    @Test
    fun markdownIsFlattenedForTheWidget() {
        assertEquals(
            "Done. Milk is on the list\n• milk\n• eggs",
            HerWidget.lineFor("**Done.** Milk is on the [list](https://x)\n\n- milk\n- eggs"),
        )
    }

    @Test
    fun noMessageFallsBackToTheEmptyLine() {
        assertNull(HerWidget.lineFor(null))
        assertNull(HerWidget.lineFor("   "))
    }

    @Test
    fun feelingWidgetPicksMainSecondAndNote() {
        val glad = HerFeeling("f", Feeling.GLAD, 0.7, "  ", null, 0, 0, "d", 1, null)
        val shown = WidgetFeeling.of(glad, listOf(Feeling.GLAD to 0.7, Feeling.CALM to 0.2, Feeling.HOPEFUL to 0.1))
        assertEquals(WidgetFeeling(Feeling.GLAD, Feeling.HOPEFUL, null), shown)
        assertEquals(WidgetFeeling(Feeling.CALM, null, null), WidgetFeeling.of(null, emptyList()))
    }
}
