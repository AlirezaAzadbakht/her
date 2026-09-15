package com.her.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.her.ui.theme.LocalReducedMotion

/** A label with a soft light sweeping across it, for honest in-flight states like "Checking…". */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val progress = rememberShimmerProgress()
    Text(text, style = style, color = color, modifier = modifier.shimmer(progress))
}

/** Placeholder bars that shimmer while real rows load. */
@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier) {
    val progress = rememberShimmerProgress()
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Column(modifier.shimmer(progress), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .fillMaxWidth(SkeletonWidths[i % SkeletonWidths.size])
                    .height(12.dp)
                    .background(bar, RoundedCornerShape(6.dp)),
            )
        }
    }
}

private val SkeletonWidths = floatArrayOf(0.72f, 0.54f, 0.66f, 0.46f, 0.6f)

@Composable
private fun rememberShimmerProgress(): State<Float> {
    if (LocalReducedMotion.current) return remember { mutableFloatStateOf(0.5f) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "shimmer-x",
    )
}

private fun Modifier.shimmer(progress: State<Float>): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val band = size.width.coerceAtLeast(160f) * 0.5f
        val x = -band + (size.width + band * 2) * progress.value
        drawRect(
            brush = Brush.linearGradient(
                0f to Color.Black.copy(alpha = 0.4f),
                0.5f to Color.Black,
                1f to Color.Black.copy(alpha = 0.4f),
                start = Offset(x - band, 0f),
                end = Offset(x + band, 0f),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
