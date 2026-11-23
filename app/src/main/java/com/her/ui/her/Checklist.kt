package com.her.ui.her

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.R
import com.her.domain.GroceryItem
import com.her.domain.GroceryStatus
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.LocalReducedMotion
import com.her.ui.theme.rememberHerHaptics

private val RowShape = RoundedCornerShape(14.dp)

/** A named list under her reply. Each item is a real checkbox; ticking one saves it and sends nothing. */
@Composable
fun Checklist(
    name: String,
    items: List<GroceryItem>,
    style: TextStyle,
    onToggle: (GroceryItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.widthIn(max = 440.dp).fillMaxWidth().padding(top = 12.dp)) {
        Text(
            stringResource(R.string.checklist_label, name),
            color = muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp).semantics { heading() },
        )
        items.forEach { item -> key(item.id) { ChecklistRow(item, style, onToggle) } }
    }
}

@Composable
private fun ChecklistRow(item: GroceryItem, style: TextStyle, onToggle: (GroceryItem, Boolean) -> Unit) {
    val checked = item.status == GroceryStatus.PURCHASED
    val reduced = LocalReducedMotion.current
    val haptics = rememberHerHaptics()
    val scheme = MaterialTheme.colorScheme
    val done by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(HerMotion.Standard, easing = HerMotion.EmphasizedDecelerate),
        label = "check",
    )
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RowShape)
            .then(if (focused) Modifier.border(2.dp, scheme.primary, RowShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Checkbox) {
                haptics.toggle(it)
                onToggle(item, it)
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckBox(done)
        Text(
            item.name,
            style = style.copy(
                fontSize = 16.sp,
                textDirection = TextDirection.Content,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
            ),
            // Done items stay readable: dimmed, never faded out.
            color = lerp(scheme.onBackground, scheme.onSurfaceVariant, done * 0.6f),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A rounded box in her glow that fills and draws its check as [done] goes 0 → 1. */
@Composable
private fun CheckBox(done: Float) {
    val colors = Her.colors
    val ink = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(lerp(outline, colors.glowCoral, done), cornerRadius = corner, style = Stroke(1.5.dp.toPx()))
        if (done <= 0f) return@Canvas
        drawRoundRect(Brush.linearGradient(listOf(colors.glowCoral, colors.glowAmber)), cornerRadius = corner, alpha = done)
        val stroke = 2.dp.toPx()
        val a = Offset(size.width * 0.26f, size.height * 0.52f)
        val b = Offset(size.width * 0.44f, size.height * 0.70f)
        val c = Offset(size.width * 0.76f, size.height * 0.32f)
        val first = (done * 2f).coerceAtMost(1f)
        drawLine(ink, a, a + (b - a) * first, stroke, StrokeCap.Round)
        if (done > 0.5f) drawLine(ink, b, b + (c - b) * ((done - 0.5f) * 2f), stroke, StrokeCap.Round)
    }
}
