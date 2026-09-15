package com.her.ui.her

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.agent.runner.Receipt
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberEntrance
import com.her.ui.theme.rememberHerHaptics
import com.her.ui.theme.rise

private const val MaxShown = 3

private enum class ReceiptAction { None, Undo, Undone }

/** A quiet note under her reply saying what she wrote, with undo for records she just created. */
@Composable
fun ReceiptLine(
    receipts: List<Receipt>,
    onUndo: (List<Receipt>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val haptics = rememberHerHaptics()
    val shown = receipts.take(MaxShown)
    val extra = receipts.size - shown.size
    val pending = receipts.filter { it.undoable && !it.undone }
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEachIndexed { index, receipt ->
            val entrance = rememberEntrance(key = receipt.label, delayMillis = index * 60, durationMillis = HerMotion.Standard)
            Row(Modifier.rise(entrance, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val struck by animateFloatAsState(
                    targetValue = if (receipt.undone) 1f else 0f,
                    animationSpec = tween(HerMotion.Emphasized, easing = HerMotion.EmphasizedDecelerate),
                    label = "receipt-strike",
                )
                Text(
                    receipt.label,
                    color = muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .graphicsLayer { alpha = 1f - 0.35f * struck }
                        .drawWithContent {
                            drawContent()
                            if (struck > 0f) {
                                val y = size.height / 2
                                val drawn = size.width * struck
                                val rtl = layoutDirection == LayoutDirection.Rtl
                                drawLine(
                                    color = muted,
                                    start = Offset(if (rtl) size.width - drawn else 0f, y),
                                    end = Offset(if (rtl) size.width else drawn, y),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                        },
                )
                val action = when {
                    receipt.undone -> ReceiptAction.Undone
                    receipt.undoable -> ReceiptAction.Undo
                    else -> ReceiptAction.None
                }
                AnimatedContent(
                    targetState = action,
                    transitionSpec = { fadeIn(HerMotion.enter()) togetherWith fadeOut(HerMotion.exit()) },
                    label = "receipt-action",
                ) { current ->
                    when (current) {
                        ReceiptAction.Undone -> Text(" · undone", color = muted, fontSize = 13.sp)
                        ReceiptAction.Undo -> Text(
                            " · undo",
                            color = accent,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    haptics.soft()
                                    onUndo(listOf(receipt))
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                        )
                        ReceiptAction.None -> Unit
                    }
                }
            }
        }
        if (extra > 0 || pending.size > 1) {
            val entrance = rememberEntrance(delayMillis = shown.size * 60, durationMillis = HerMotion.Standard)
            Row(Modifier.rise(entrance, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (extra > 0) Text("and $extra more", color = muted, fontSize = 13.sp)
                if (pending.size > 1) {
                    Text(
                        if (extra > 0) " · undo all" else "undo all",
                        color = accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                haptics.soft()
                                onUndo(pending)
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
