package com.her.ui.orbs

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.her.agent.runner.TurnState
import com.her.ui.theme.HaloShader
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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

data class PresenceLook(val state: OrbState, val palette: Int)

val ThinkingStates = listOf(
    OrbState.Breathing,
    OrbState.Shaping,
    OrbState.Weaving,
    OrbState.Composing,
    OrbState.Connecting,
    OrbState.Working,
)

fun nextLook(prev: PresenceLook, paletteCount: Int, random: Random = Random): PresenceLook {
    val states = ThinkingStates.filter { it != prev.state }
    val palettes = (0 until paletteCount).filter { it != prev.palette }
    return PresenceLook(states.random(random), palettes.random(random))
}

/** Glides each of the three palette colors toward [target], so a feeling change washes in. */
@Composable
fun animatedPalette(target: List<Color>): List<Color> = target.mapIndexed { i, c ->
    animateColorAsState(c, tween(HerMotion.Emphasized * 3), label = "palette-$i").value
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
    palette: List<Color> = Her.colors.glow,
) {
    val colors = Her.colors
    val tint = remember(palette, colors.orbInkFar, colors.orbInkNear) {
        OrbTint(colors.orbInkFar, colors.orbInkNear, palette)
    }
    val animatedEnergy by animateFloatAsState(energy, HerMotion.gentle(), label = "presence-energy")
    Box(modifier.size(orbSize * haloScale), contentAlignment = Alignment.Center) {
        Halo(energy = { animatedEnergy }, live = !paused, palette = palette, modifier = Modifier.matchParentSize())
        AnimatedContent(
            targetState = OrbPose(state, paused),
            transitionSpec = {
                (fadeIn(HerMotion.enter()) + scaleIn(HerMotion.enter(), initialScale = 0.9f)) togetherWith
                    (fadeOut(tween(HerMotion.Quick)) + scaleOut(tween(HerMotion.Quick), targetScale = 0.9f))
            },
            contentAlignment = Alignment.Center,
            label = "presence-orb",
        ) { pose ->
            ThinkingOrb(pose.state, size = size, displaySize = orbSize, paused = pose.paused, tint = tint)
        }
    }
}

@Composable
private fun Halo(energy: () -> Float, live: Boolean, palette: List<Color>, modifier: Modifier) {
    val c1 = palette[0]
    val c2 = palette[1]
    val c3 = palette[2]
    val clock = LocalOrbClock.current
    val animate = live && clock != null && !LocalReducedMotion.current
    SubscribeOrbClock(clock, animate)
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { HaloShader() } else null
    Canvas(modifier) {
        val t = if (animate) clock!!.seconds.toFloat() else 0f
        val e = energy().coerceIn(0f, 1f)
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            shader.update(size.width, size.height, t, e, c1, c2, c3)
            drawRect(shader.brush)
        } else {
            val breath = if (animate) 0.5f + 0.5f * sin(t * (0.8f + 1.2f * e)) else 0.5f
            val radius = size.minDimension / 2 * (0.62f + 0.14f * e + 0.05f * breath)
            val alpha = 0.16f + 0.34f * e
            val drift = if (animate) {
                Offset(size.width * 0.12f * sin(t * 0.35f), size.height * 0.12f * cos(t * 0.28f))
            } else {
                Offset.Zero
            }
            drawCircle(
                brush = Brush.radialGradient(
                    0f to c1.copy(alpha = alpha),
                    0.45f to c2.copy(alpha = alpha * 0.55f),
                    0.75f to c3.copy(alpha = alpha * 0.25f),
                    1f to Color.Transparent,
                    center = center + drift,
                    radius = radius,
                ),
                radius = radius,
                center = center + drift,
            )
        }
    }
}
