package com.her

import com.her.ui.markdown.ContentDirection
import com.her.ui.markdown.MarkdownParser
import com.her.ui.markdown.MdBlock
import com.her.ui.markdown.MdInline
import com.her.ui.markdown.firstStrongDirection
import com.her.ui.markdown.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun boldAndEmphasis() {
        val inlines = MarkdownParser.parseInline("I noted it: **get a 15-watt lamp** for the living room.")
        assertTrue(inlines.any { it is MdInline.Strong })
        val strong = inlines.filterIsInstance<MdInline.Strong>().single()
        assertEquals("get a 15-watt lamp", (strong.children.single() as MdInline.Text).value)
    }

    @Test
    fun listAndHeading() {
        val blocks = MarkdownParser.parse(
            """
            ## Tonight
            - milk
            - **eggs**
            """.trimIndent(),
        )
        assertTrue(blocks[0] is MdBlock.Heading)
        val list = blocks[1] as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        assertTrue(list.items[1].any { it is MdInline.Strong })
    }

    @Test
    fun firstStrongPersianIsRtl() {
        assertEquals(ContentDirection.Rtl, firstStrongDirection("سلام، خوبی؟"))
        assertEquals(ContentDirection.Rtl, firstStrongDirection("مرحبا"))
        assertEquals(ContentDirection.Rtl, firstStrongDirection("שלום"))
    }

    @Test
    fun firstStrongSkipsNeutralsThenFollowsScript() {
        assertEquals(ContentDirection.Rtl, firstStrongDirection("...123 سلام"))
        assertEquals(ContentDirection.Ltr, firstStrongDirection("42 hello"))
        assertEquals(ContentDirection.Ltr, firstStrongDirection("Hello سلام"))
        assertEquals(null, firstStrongDirection("... 123 !!!"))
    }

    @Test
    fun inlinePlainTextIgnoresMarkers() {
        val inlines = MarkdownParser.parseInline("**سلام** دنیا")
        assertEquals("سلام دنیا", inlines.plainText())
        assertEquals(ContentDirection.Rtl, firstStrongDirection(inlines.plainText()))
    }
}
