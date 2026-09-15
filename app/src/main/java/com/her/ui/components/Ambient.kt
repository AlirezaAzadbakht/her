package com.her.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.her.ui.theme.GrainShader
import com.her.ui.theme.Her
import com.her.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

// The drift is 20s+ slow, so ~15 updates a second reads as smooth and keeps an idle screen cheap.
private const val DriftStepMs = 66L

/** The warm night (or dawn) ground: two slow drifting lights and a whisper of film grain. */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Her.colors
    val ground = MaterialTheme.colorScheme.background
    val reduced = LocalReducedMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val drift = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced, lifecycle) {
        if (reduced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = SystemClock.uptimeMillis() - (drift.floatValue * 1000).toLong()
            while (true) {
                drift.floatValue = (SystemClock.uptimeMillis() - start) / 1000f
                delay(DriftStepMs)
            }
        }
    }
    val grain = rememberGrainBrush()
    Box(
        modifier = modifier.drawWithCache {
            val reach = max(size.width, size.height)
            onDrawBehind {
                drawRect(ground)
                val t = drift.floatValue
                val warm = Offset(
                    size.width * (0.22f + 0.12f * sin(t * 0.05f)),
                    size.height * (0.16f + 0.06f * cos(t * 0.037f)),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.glowCoral.copy(alpha = colors.ambientAlpha), Color.Transparent),
                        center = warm,
                        radius = reach * 0.7f,
                    ),
                )
                val rose = Offset(
                    size.width * (0.84f + 0.1f * cos(t * 0.043f)),
                    size.height * (0.84f + 0.07f * sin(t * 0.031f)),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.glowRose.copy(alpha = colors.ambientAlpha * 0.75f), Color.Transparent),
                        center = rose,
                        radius = reach * 0.62f,
                    ),
                )
                drawRect(grain, alpha = colors.grainAlpha)
            }
        },
        content = content,
    )
}

@Composable
private fun rememberGrainBrush(): Brush = remember {
    if (Build.VERSION.SDK_INT >= 33) {
        GrainShader.brush()
    } else {
        ShaderBrush(ImageShader(noiseTile(), TileMode.Repeated, TileMode.Repeated))
    }
}

private fun noiseTile(side: Int = 128): androidx.compose.ui.graphics.ImageBitmap {
    val random = Random(7)
    val pixels = IntArray(side * side) {
        val v = random.nextFloat() * 2f - 1f
        val alpha = (kotlin.math.abs(v) * 255).toInt()
        val level = if (v > 0f) 0xFF else 0x00
        (alpha shl 24) or (level shl 16) or (level shl 8) or level
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}
