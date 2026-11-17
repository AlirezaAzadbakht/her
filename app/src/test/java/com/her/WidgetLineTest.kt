package com.her

import com.her.widget.HerWidget
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
}
