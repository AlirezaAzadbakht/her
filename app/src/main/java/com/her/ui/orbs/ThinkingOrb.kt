package com.her.ui.orbs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.her.ui.theme.Her
import com.her.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlinx.coroutines.flow.collectLatest

/**
 * One clock for every orb on screen, so they stay in phase like upstream's shared clock.
 * Frames tick only while the app is resumed, reduced motion is off, and some orb is live.
 */
@Stable
class OrbClock internal constructor() {
    var seconds by mutableDoubleStateOf(0.0)
        internal set
    internal var subscribers by mutableIntStateOf(0)
}

val LocalOrbClock = staticCompositionLocalOf<OrbClock?> { null }

@Composable
fun ProvideOrbClock(content: @Composable () -> Unit) {
    val clock = remember { OrbClock() }
    val reduced = LocalReducedMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, reduced, lifecycle) {
        if (reduced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { clock.subscribers > 0 }.collectLatest { active ->
                if (!active) return@collectLatest
                // Continue from where the clock stopped, so a pause never makes orbs jump.
                val base = clock.seconds
                var origin = -1L
                while (true) {
                    withFrameNanos { nanos ->
                        if (origin < 0) origin = nanos
                        clock.seconds = base + (nanos - origin) / 1e9
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalOrbClock provides clock, content = content)
}

/** Keeps [clock] ticking while this composable is live. */
@Composable
internal fun SubscribeOrbClock(clock: OrbClock?, live: Boolean) {
    DisposableEffect(clock, live) {
        if (clock != null && live) {
            clock.subscribers += 1
            onDispose { clock.subscribers -= 1 }
        } else {
            onDispose { }
        }
    }
}

/**
 * Her's ink for the orbs. Upstream paints grey; here strength (how visible a mark is)
 * blends a faint far ink toward a strong ink tinted around the coral, amber, rose wheel.
 */
@Immutable
class OrbTint(far: Color, near: Color, glow: List<Color>, private val glowMix: Float = 0.55f) {
    private val farC = floatArrayOf(far.red, far.green, far.blue)
    private val nearC = floatArrayOf(near.red, near.green, near.blue)
    private val glowC = FloatArray(glow.size * 3).also { out ->
        glow.forEachIndexed { i, c ->
            out[i * 3] = c.red
            out[i * 3 + 1] = c.green
            out[i * 3 + 2] = c.blue
        }
    }
    private val glowCount = glow.size

    internal fun color(strength: Float, hue: Float, alpha: Float): Color {
        val scaled = hue * glowCount
        val i = scaled.toInt().coerceIn(0, glowCount - 1)
        val j = (i + 1) % glowCount
        val f = scaled - scaled.toInt()
        val r = channel(0, i, j, f, strength)
        val g = channel(1, i, j, f, strength)
        val b = channel(2, i, j, f, strength)
        return Color(r, g, b, alpha)
    }

    private fun channel(c: Int, i: Int, j: Int, f: Float, strength: Float): Float {
        val glow = glowC[i * 3 + c] + (glowC[j * 3 + c] - glowC[i * 3 + c]) * f
        val strong = glow + (nearC[c] - glow) * (1f - glowMix)
        return (farC[c] + (strong - farC[c]) * strength).coerceIn(0f, 1f)
    }
}

@Composable
fun rememberOrbTint(): OrbTint {
    val colors = Her.colors
    return remember(colors) { OrbTint(colors.orbInkFar, colors.orbInkNear, colors.glow) }
}

/**
 * A thinking-orbs loader. [size] picks the tuned design; [displaySize] scales the drawing
 * (not a bitmap) so it stays sharp. Paused or reduced motion shows upstream's static instant.
 */
@Composable
fun ThinkingOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    size: OrbSize = OrbSize.Px64,
    displaySize: Dp = size.value.dp,
    speed: Double = 1.0,
    paused: Boolean = false,
    tint: OrbTint = rememberOrbTint(),
) {
    val clock = LocalOrbClock.current
    val live = clock != null && !paused && !LocalReducedMotion.current
    SubscribeOrbClock(clock, live)
    val preset = remember(state, size) { resolvePreset(state, size) }
    val label = state.label
    val design = size.value.toDouble()
    Canvas(modifier.size(displaySize).semantics { contentDescription = label }) {
        val seconds = if (live) clock!!.seconds else 0.0
        val t = (if (live) seconds else OrbSpec.REDUCED_MOTION_T) * preset.speed * speed
        val frame = orbFrame(preset, design, t)
        val zoom = (this.size.minDimension / design).toFloat()
        val center = design / 2
        val drift = (seconds * 0.03).toFloat()
        for (l in frame.lines) {
            val hue = hueAt((l.x1 + l.x2) / 2 - center, (l.y1 + l.y2) / 2 - center, drift)
            drawLine(
                color = tint.color(strengthOf(l.white), hue, l.a.toFloat().coerceIn(0f, 1f)),
                start = Offset(l.x1.toFloat() * zoom, l.y1.toFloat() * zoom),
                end = Offset(l.x2.toFloat() * zoom, l.y2.toFloat() * zoom),
                strokeWidth = l.w.toFloat() * zoom,
            )
        }
        for (d in frame.dots) {
            drawCircle(
                color = tint.color(strengthOf(d.white), hueAt(d.x - center, d.y - center, drift), d.a.toFloat().coerceIn(0f, 1f)),
                radius = d.r.toFloat() * zoom,
                center = Offset(d.x.toFloat() * zoom, d.y.toFloat() * zoom),
            )
        }
    }
}

// Upstream ink: white 0 is the strongest mark on either theme.
private fun strengthOf(white: Double): Float = 1f - white.toFloat().coerceIn(0f, 1f)

private fun hueAt(dx: Double, dy: Double, drift: Float): Float {
    val u = (atan2(dy, dx) / (2 * PI)).toFloat() + 0.5f + drift
    return u - floor(u)
}
