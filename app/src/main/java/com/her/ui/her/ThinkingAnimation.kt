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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun ThinkingAnimation(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val glow = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "think-alpha",
    )
    val motion = rememberInfiniteTransition(label = "think")
    val breath by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val wave by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "wave",
    )
    val lift by motion.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            tween(4200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "lift",
    )

    Box(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        if (alpha <= 0.01f) return@Box
        Canvas(Modifier.size(168.dp)) {
            val radius = size.minDimension * (0.34f + 0.06f * breath)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glow.copy(alpha = 0.16f + 0.08f * breath),
                        glow.copy(alpha = 0.05f),
                        glow.copy(alpha = 0f),
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
                radius = radius,
            )
        }
        Row(
            modifier = Modifier.offset(y = lift.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val phase = (sin((wave - index / 3f) * 2.0 * PI).toFloat() + 1f) / 2f
                Box(
                    modifier = Modifier
                        .size((6.5f + 1.8f * phase).dp)
                        .offset(y = (-4f * phase).dp)
                        .graphicsLayer { this.alpha = 0.28f + 0.62f * phase }
                        .background(ink.copy(alpha = 0.78f), CircleShape),
                )
            }
        }
    }
}
