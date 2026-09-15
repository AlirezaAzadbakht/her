package com.her.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion

/** A translucent container whose hairline edge warms into a gradient when [focused]. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Her.colors
    val glow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(HerMotion.Standard, easing = HerMotion.StandardEasing),
        label = "glass-focus",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.glassFill)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val gradient = Brush.linearGradient(colors.glow, start = Offset.Zero, end = Offset(size.width, size.height))
                // the clip hides the outer half of the stroke, so 2dp draws a 1dp hairline
                val stroke = Stroke(2.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawOutline(outline, colors.glassStroke, style = stroke)
                    if (glow > 0f) drawOutline(outline, gradient, alpha = glow * 0.9f, style = stroke)
                }
            },
        content = content,
    )
}
