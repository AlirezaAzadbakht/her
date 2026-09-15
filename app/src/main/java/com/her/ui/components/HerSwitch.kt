package com.her.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.her.ui.theme.Her
import com.her.ui.theme.rememberHerHaptics
import kotlin.math.PI
import kotlin.math.sin

/** A switch whose track fills with the glow gradient and whose thumb stretches mid-travel. */
@Composable
fun HerSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "switch",
    )
    Canvas(
        modifier
            .size(width = 46.dp, height = 28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) {
                haptics.toggle(it)
                onCheckedChange(it)
            },
    ) {
        val p = progress.coerceIn(0f, 1f)
        val track = CornerRadius(size.height / 2)
        drawRoundRect(scheme.onSurfaceVariant.copy(alpha = 0.22f), cornerRadius = track)
        if (p > 0f) {
            drawRoundRect(Brush.horizontalGradient(listOf(colors.glowCoral, colors.glowAmber)), alpha = p, cornerRadius = track)
        }
        val pad = 4.dp.toPx()
        val knob = size.height - pad * 2
        val stretch = knob * 0.3f * sin(PI.toFloat() * p)
        val left = pad + (size.width - pad * 2 - knob) * p
        drawRoundRect(
            color = lerp(scheme.onSurfaceVariant, scheme.background, p),
            topLeft = Offset(left - stretch / 2, pad),
            size = Size(knob + stretch, knob),
            cornerRadius = CornerRadius(knob / 2),
        )
    }
}
