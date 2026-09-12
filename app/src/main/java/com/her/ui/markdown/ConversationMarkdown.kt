package com.her.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.ui.theme.ConversationStyle

@Composable
fun ConversationMarkdown(
    content: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = ConversationStyle,
) {
    val blocks = remember(content) { MarkdownParser.parse(content) }
    val link = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val top = if (index == 0) 0.dp else 8.dp
            when (block) {
                is MdBlock.Paragraph -> {
                    Text(
                        text = annotate(block.inlines, color, link),
                        style = style,
                        color = color,
                        modifier = Modifier.padding(top = top),
                    )
                }
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 24.sp
                        2 -> 22.sp
                        else -> 20.sp
                    }
                    Text(
                        text = annotate(block.inlines, color, link),
                        style = style.copy(fontSize = size, fontWeight = FontWeight.Medium, lineHeight = (size.value + 8).sp),
                        color = color,
                        modifier = Modifier.padding(top = top),
                    )
                }
                is MdBlock.Quote -> {
                    Text(
                        text = annotate(block.inlines, color, link),
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = color.copy(alpha = 0.86f),
                        modifier = Modifier.padding(top = top, start = 12.dp),
                    )
                }
                is MdBlock.Code -> {
                    SelectionContainer {
                        Text(
                            text = block.text,
                            style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp),
                            color = color,
                            modifier = Modifier
                                .padding(top = top)
                                .fillMaxWidth()
                                .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                is MdBlock.ListBlock -> {
                    Column(Modifier.padding(top = top)) {
                        block.items.forEachIndexed { itemIndex, item ->
                            Row(Modifier.padding(bottom = 4.dp)) {
                                Text(
                                    text = if (block.ordered) "${itemIndex + 1}.  " else "•  ",
                                    style = style,
                                    color = color,
                                )
                                Text(
                                    text = annotate(item, color, link),
                                    style = style,
                                    color = color,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun annotate(inlines: List<MdInline>, color: Color, link: Color): AnnotatedString = buildAnnotatedString {
    inlines.forEach { appendInline(it, color, link) }
}

private fun AnnotatedString.Builder.appendInline(inline: MdInline, color: Color, link: Color) {
    when (inline) {
        is MdInline.Text -> append(inline.value)
        is MdInline.Code -> withStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.10f), fontSize = 16.sp),
        ) { append(inline.value) }
        is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            inline.children.forEach { appendInline(it, color, link) }
        }
        is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            inline.children.forEach { appendInline(it, color, link) }
        }
        is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            inline.children.forEach { appendInline(it, color, link) }
        }
        is MdInline.Link -> withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
            inline.label.forEach { appendInline(it, link, link) }
        }
    }
}
