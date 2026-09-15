package com.her.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.her.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

// Each word fades over this many steps, so neighbours overlap instead of blinking on in turn.
private const val FadeSteps = 3f

/**
 * Words ink in one after another while the line settles upward. One Text keeps wrapping and
 * bidi shaping intact, so Persian names read correctly. [onFinished] fires once all are in.
 */
@Composable
fun StaggeredText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    startDelayMillis: Int = 0,
    stepMillis: Int = 90,
    onFinished: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val words = remember(text) { text.split(' ') }
    val end = words.size + FadeSteps
    val progress = remember(text) { Animatable(if (reduced) end else 0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(text) {
        if (progress.value < end) {
            delay(startDelayMillis.toLong())
            progress.animateTo(end, tween((stepMillis * end).toInt(), easing = LinearEasing))
        }
        finished()
    }
    val p = progress.value
    val annotated = buildAnnotatedString {
        words.forEachIndexed { i, word ->
            if (i > 0) append(' ')
            val f = ((p - i) / FadeSteps).coerceIn(0f, 1f)
            val eased = 1f - (1f - f) * (1f - f)
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * eased))) { append(word) }
        }
    }
    Text(
        annotated,
        style = style,
        modifier = modifier.graphicsLayer {
            val settle = (progress.value / end).coerceIn(0f, 1f)
            translationY = (1f - settle) * (1f - settle) * 10.dp.toPx()
        },
    )
}
