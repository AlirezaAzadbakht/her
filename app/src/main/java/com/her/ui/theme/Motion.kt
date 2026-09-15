package com.her.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One vocabulary for every animation in the app. */
object HerMotion {
    const val Quick = 160
    const val Standard = 320
    const val Emphasized = 560
    const val Ambient = 3600

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    fun <T> enter(durationMillis: Int = Standard, delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis, delayMillis, EmphasizedDecelerate)
    fun <T> exit(durationMillis: Int = Quick): TweenSpec<T> = tween(durationMillis, easing = EmphasizedAccelerate)
}

/** True when the system animator scale is off; loops, shaders and orbs hold still. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(readReducedMotion(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** 0 → 1 once when first composed (or when [key] changes); already 1 under reduced motion. */
@Composable
fun rememberEntrance(
    key: Any? = Unit,
    delayMillis: Int = 0,
    durationMillis: Int = HerMotion.Emphasized,
): State<Float> {
    val reduced = LocalReducedMotion.current
    val progress = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(durationMillis, delayMillis, HerMotion.EmphasizedDecelerate))
        }
    }
    return progress.asState()
}

/** Fades in while rising [distance]; reads progress in the draw phase only. */
fun Modifier.rise(progress: State<Float>, distance: Dp = 14.dp): Modifier = graphicsLayer {
    val p = progress.value
    alpha = p
    translationY = (1f - p) * distance.toPx()
}

/** A short damped horizontal shake each time [trigger] increases past 0. */
@Composable
fun rememberShake(trigger: Int): State<Float> {
    val offset = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduced) return@LaunchedEffect
        for (x in floatArrayOf(10f, -8f, 6f, -4f, 2f, 0f)) {
            offset.animateTo(x, tween(45, easing = LinearEasing))
        }
    }
    return offset.asState()
}

fun Modifier.shake(offset: State<Float>): Modifier = graphicsLayer { translationX = offset.value * density }
