package com.her.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.ui.theme.ConversationStyle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding

@Composable
fun ConversationMarkdown(
    content: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = ConversationStyle,
) {
    val body = style.copy(color = color)
    Markdown(
        content = content,
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(text = color),
        typography = markdownTypography(
            h1 = body.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
            h2 = body.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
            h3 = body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            h4 = body.copy(fontWeight = FontWeight.Medium),
            h5 = body.copy(fontWeight = FontWeight.Medium),
            h6 = body.copy(fontWeight = FontWeight.Medium),
            text = body,
            paragraph = body,
            quote = body.copy(fontStyle = FontStyle.Italic),
            ordered = body,
            bullet = body,
            list = body,
            code = body.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp),
            inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
            table = body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            textLink = TextLinkStyles(
                style = body.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ).toSpanStyle(),
            ),
        ),
        padding = markdownPadding(block = 6.dp, listItemTop = 2.dp, listItemBottom = 2.dp),
        animations = markdownAnimations(animateTextSize = { this }),
        error = { Text(content, style = body, color = color, modifier = it) },
    )
}
