package com.her

import com.her.ui.markdown.MarkdownParser
import com.her.ui.markdown.MdBlock
import com.her.ui.markdown.MdInline
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
}
