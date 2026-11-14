package com.her.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.R
import com.her.ui.theme.HerMotion

/** A plain message with the raw error one tap away, for someone debugging their own endpoint. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteWithDetails(
    text: String,
    details: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    centered: Boolean = false,
    actions: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    var open by remember(details) { mutableStateOf(false) }
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    // Follow the words, not the phone's locale: a Persian line stays RTL and a raw error stays LTR.
    val style = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text,
            style = style,
            color = color,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        if (actions != null || details != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, align),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                actions?.invoke(this)
                if (details != null) {
                    HerButton(stringResource(if (open) R.string.hide_details else R.string.details), tone = HerButtonTone.Quiet, onClick = { open = !open })
                }
            }
        }
        AnimatedVisibility(
            visible = open && details != null,
            enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
            exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
        ) {
            SelectionContainer {
                Text(
                    details.orEmpty(),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
