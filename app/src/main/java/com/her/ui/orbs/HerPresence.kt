package com.her.ui.orbs

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.her.agent.runner.TurnState
import com.her.ui.theme.HaloShader
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.LocalReducedMotion
import kotlin.math.sin

/** What her presence is doing, derived from the turn and the composer. */
enum class PresenceMood { Idle, Listening, Thinking, Speaking, Error }

fun presenceFor(turn: TurnState, revealing: Boolean, draftNotEmpty: Boolean, hasError: Boolean): PresenceMood = when {
    turn is TurnState.Thinking -> PresenceMood.Thinking
    turn is TurnState.Streaming && turn.text.isBlank() -> PresenceMood.Thinking
    turn is TurnState.Streaming || revealing -> PresenceMood.Speaking
    hasError -> PresenceMood.Error
    draftNotEmpty -> PresenceMood.Listening
    else -> PresenceMood.Idle
}

private data class OrbPose(val state: OrbState, val paused: Boolean)

/**
 * Her signature: a thinking orb inside a breathing warm halo. [energy] (0..1) swells the halo;
 * changing [state] crossfades the orbs. The halo animates only while the orb is live.
 */
@Composable
fun HerPresence(
    state: OrbState,
    orbSize: Dp,
    modifier: Modifier = Modifier,
    size: OrbSize = OrbSize.Px64,
    energy: Float = 0.5f,
    paused: Boolean = false,
    haloScale: Float = 1.9f,
) {
    val animatedEnergy by animateFloatAsState(energy, HerMotion.gentle(), label = "presence-energy")
    Box(modifier.size(orbSize * haloScale), contentAlignment = Alignment.Center) {
        Halo(energy = { animatedEnergy }, live = !paused, modifier = Modifier.matchParentSize())
        AnimatedContent(
            targetState = OrbPose(state, paused),
            transitionSpec = {
                (fadeIn(HerMotion.enter()) + scaleIn(HerMotion.enter(), initialScale = 0.9f)) togetherWith
                    (fadeOut(tween(HerMotion.Quick)) + scaleOut(tween(HerMotion.Quick), targetScale = 0.9f))
            },
            contentAlignment = Alignment.Center,
            label = "presence-orb",
        ) { pose ->
            ThinkingOrb(pose.state, size = size, displaySize = orbSize, paused = pose.paused)
        }
    }
}

@Composable
private fun Halo(energy: () -> Float, live: Boolean, modifier: Modifier) {
    val colors = Her.colors
    val clock = LocalOrbClock.current
    val animate = live && clock != null && !LocalReducedMotion.current
    SubscribeOrbClock(clock, animate)
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { HaloShader() } else null
    Canvas(modifier) {
        val t = if (animate) clock!!.seconds.toFloat() else 0f
        val e = energy().coerceIn(0f, 1f)
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            shader.update(size.width, size.height, t, e, colors.halo, colors.glowRose)
            drawRect(shader.brush)
        } else {
            val breath = if (animate) 0.5f + 0.5f * sin(t * (0.8f + 1.2f * e)) else 0.5f
            val radius = size.minDimension / 2 * (0.62f + 0.14f * e + 0.05f * breath)
            val alpha = 0.16f + 0.34f * e
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colors.halo.copy(alpha = alpha),
                    0.5f to colors.glowRose.copy(alpha = alpha * 0.4f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius,
                ),
                radius = radius,
            )
        }
    }
}
