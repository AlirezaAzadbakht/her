package com.her.ui.her

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ThinkingAnimation(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "think-alpha",
    )
    val motion = rememberInfiniteTransition(label = "think")
    val phaseA by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing)),
        label = "phase-a",
    )
    val phaseB by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "phase-b",
    )
    val pulse by motion.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Canvas(modifier.graphicsLayer { this.alpha = alpha }) {
        if (alpha <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        val span = min(w, h)
        val twoPi = (2.0 * PI).toFloat()

        fun bloom(cx: Float, cy: Float, radius: Float, strength: Float, tint: Color = color) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.72f * strength),
                        tint.copy(alpha = 0.28f * strength),
                        tint.copy(alpha = 0f),
                    ),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }

        bloom(
            cx = w * 0.5f,
            cy = h * 0.48f,
            radius = span * (0.42f + 0.10f * pulse),
            strength = 0.45f + 0.25f * pulse,
        )
        bloom(
            cx = w * (0.32f + 0.12f * sin(phaseA * twoPi)),
            cy = h * (0.36f + 0.10f * cos(phaseA * twoPi)),
            radius = span * (0.38f + 0.08f * pulse),
            strength = pulse,
        )
        bloom(
            cx = w * (0.68f + 0.10f * cos(phaseB * twoPi)),
            cy = h * (0.54f + 0.12f * sin(phaseB * twoPi)),
            radius = span * (0.34f + 0.08f * (1f - pulse)),
            strength = 0.9f,
        )
        bloom(
            cx = w * (0.50f + 0.08f * sin((phaseA + phaseB) * PI.toFloat())),
            cy = h * (0.58f + 0.07f * cos(phaseA * twoPi)),
            radius = span * 0.26f,
            strength = 0.7f + 0.3f * pulse,
        )
    }
}
