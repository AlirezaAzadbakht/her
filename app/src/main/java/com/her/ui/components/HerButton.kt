package com.her.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.ui.orbs.OrbSize
import com.her.ui.orbs.OrbState
import com.her.ui.orbs.ThinkingOrb
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberHerHaptics

enum class HerButtonTone { Primary, Quiet }

private enum class ButtonFace { Label, Loading, Success }

/**
 * A pill button. While [loading] the label gives way to a thinking orb (and optional
 * [loadingText]); [success] draws a check. The pill resizes smoothly between faces.
 */
@Composable
fun HerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
    loadingOrb: OrbState = OrbState.Connecting,
    success: Boolean = false,
    tone: HerButtonTone = HerButtonTone.Primary,
) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, HerMotion.snappy(), label = "button-press")
    val alpha by animateFloatAsState(if (enabled || loading) 1f else 0.42f, tween(HerMotion.Standard), label = "button-alpha")
    val face = when {
        loading -> ButtonFace.Loading
        success -> ButtonFace.Success
        else -> ButtonFace.Label
    }
    val primary = tone == HerButtonTone.Primary
    val fill = if (primary) {
        Brush.horizontalGradient(listOf(colors.glowCoral.copy(alpha = 0.22f), colors.glowAmber.copy(alpha = 0.14f)))
    } else {
        SolidColor(Color.Transparent)
    }
    val edge = if (primary) {
        Brush.horizontalGradient(listOf(colors.glowCoral.copy(alpha = 0.75f), colors.glowAmber.copy(alpha = 0.5f), colors.glowRose.copy(alpha = 0.65f)))
    } else {
        SolidColor(Color.Transparent)
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, edge, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
            ) {
                haptics.tick()
                onClick()
            }
            .animateContentSize(HerMotion.gentle())
            .padding(horizontal = if (primary) 22.dp else 12.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = face,
            transitionSpec = {
                (fadeIn(HerMotion.enter(delayMillis = 60)) + scaleIn(HerMotion.enter(delayMillis = 60), initialScale = 0.85f)) togetherWith
                    fadeOut(tween(HerMotion.Quick))
            },
            contentAlignment = Alignment.Center,
            label = "button-face",
        ) { current ->
            when (current) {
                ButtonFace.Label -> Text(
                    text,
                    color = if (primary) scheme.onBackground else scheme.primary,
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.6.sp),
                )
                ButtonFace.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThinkingOrb(loadingOrb, size = OrbSize.Px20, displaySize = 22.dp)
                    if (loadingText != null) {
                        ShimmerText(loadingText, style = MaterialTheme.typography.labelLarge, color = scheme.onBackground)
                    }
                }
                ButtonFace.Success -> DrawnCheck(scheme.primary)
            }
        }
    }
}

@Composable
private fun DrawnCheck(color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(420, easing = HerMotion.EmphasizedDecelerate)) }
    Canvas(Modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.54f)
            lineTo(size.width * 0.42f, size.height * 0.76f)
            lineTo(size.width * 0.84f, size.height * 0.28f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val drawn = Path()
        measure.getSegment(0f, measure.length * progress.value, drawn, true)
        drawPath(drawn, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
