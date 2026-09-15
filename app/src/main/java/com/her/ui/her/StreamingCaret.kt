package com.her.ui.her

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.her.ui.theme.Her
import com.her.ui.theme.LocalReducedMotion

/** A small warm dot that breathes where the next words will land. */
@Composable
fun StreamingCaret(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val glow = Her.colors.glowAmber
    val pulse = rememberCaretPulse()
    Canvas(modifier.size(8.dp)) {
        val p = pulse.value
        val r = size.minDimension / 2
        drawCircle(
            brush = Brush.radialGradient(listOf(glow.copy(alpha = 0.5f * p), Color.Transparent), center = center, radius = r),
            radius = r,
        )
        drawCircle(color.copy(alpha = 0.45f + 0.5f * p), radius = r * (0.42f + 0.14f * p))
    }
}

@Composable
private fun rememberCaretPulse(): State<Float> {
    if (LocalReducedMotion.current) return remember { mutableFloatStateOf(0.7f) }
    return rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "caret-glow",
    )
}
