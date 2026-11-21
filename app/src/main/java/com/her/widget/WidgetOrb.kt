package com.her.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.her.ui.orbs.OrbSize
import com.her.ui.orbs.OrbSpec
import com.her.ui.orbs.OrbState
import com.her.ui.orbs.OrbTint
import com.her.ui.orbs.ResolvedPreset
import com.her.ui.orbs.hueAt
import com.her.ui.orbs.orbFrame
import com.her.ui.orbs.resolvePreset
import com.her.ui.orbs.strengthOf
import com.her.ui.theme.herColors

/**
 * The app's own orbs drawn into bitmaps, since a widget can't run the live drawing: the Memory tab's
 * feeling orb as a still, and the chat's thinking orb (inside HerPresence's halo) as a loop while she replies.
 */
object WidgetOrb {
    private val preset = resolvePreset(OrbState.Breathing, OrbSize.Px64)
    private val feelingPreset = resolvePreset(OrbState.Breathing, OrbSize.Px20)

    // ponytail: the ring never repeats exactly; 7.35 engine seconds is its closest return
    // (searched over 1.5..8), so the loop seam is about one frame's jump. Re-search if the ring's maths changes.
    private const val LOOP = 7.35
    const val FRAMES = 24

    /** The Memory tab's "How she feels" orb: the small Breathing ring, paused, in Her's own glow, no halo. */
    fun feeling(dark: Boolean, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawMarks(Canvas(bitmap), feelingPreset, OrbSize.Px20.value.toDouble(), OrbSpec.REDUCED_MOTION_T * feelingPreset.speed, herColors(dark).glow, dark, px.toFloat(), 0f)
        return bitmap
    }

    /** The chat row's orb at rest: the thinking orb's paused instant, a quiet halo. */
    fun still(palette: List<Color>, dark: Boolean, px: Int): Bitmap =
        draw(palette, dark, px, OrbSpec.REDUCED_MOTION_T * preset.speed, energy = 0.25f)

    /** One loop of the ring, halo lit as when she thinks. At widget_thinking_orb's 95 ms a frame it plays at the app's pace. */
    fun frames(palette: List<Color>, dark: Boolean, px: Int): List<Bitmap> =
        List(FRAMES) { draw(palette, dark, px, it * LOOP / FRAMES, energy = 0.85f) }

    private fun draw(palette: List<Color>, dark: Boolean, px: Int, t: Double, energy: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = px / 2f

        // HerPresence's halo (its pre-Android 13 gradient), at the middle of its breath.
        val radius = c * (0.62f + 0.14f * energy + 0.025f)
        val alpha = 0.16f + 0.34f * energy
        paint.shader = RadialGradient(
            c, c, radius,
            intArrayOf(
                palette[0].copy(alpha = alpha).toArgb(),
                palette[1].copy(alpha = alpha * 0.55f).toArgb(),
                palette[2].copy(alpha = alpha * 0.25f).toArgb(),
                Color.Transparent.toArgb(),
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(c, c, radius, paint)
        paint.shader = null

        // The orb is 1/1.9 of the halo box, as in HerPresence.
        drawMarks(canvas, preset, OrbSize.Px64.value.toDouble(), t, palette, dark, px / 1.9f, (px - px / 1.9f) / 2f)
        return bitmap
    }

    /** ThinkingOrb's marks for [t], [design] points scaled to [side] px at [off]. */
    private fun drawMarks(canvas: Canvas, preset: ResolvedPreset, design: Double, t: Double, palette: List<Color>, dark: Boolean, side: Float, off: Float) {
        val colors = herColors(dark)
        val tint = OrbTint(colors.orbInkFar, colors.orbInkNear, palette)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val frame = orbFrame(preset, design, t)
        val zoom = (side / design).toFloat()
        val center = design / 2
        for (l in frame.lines) {
            val hue = hueAt((l.x1 + l.x2) / 2 - center, (l.y1 + l.y2) / 2 - center, 0f)
            paint.color = tint.color(strengthOf(l.white), hue, l.a.toFloat().coerceIn(0f, 1f)).toArgb()
            paint.strokeWidth = l.w.toFloat() * zoom
            canvas.drawLine(off + l.x1.toFloat() * zoom, off + l.y1.toFloat() * zoom, off + l.x2.toFloat() * zoom, off + l.y2.toFloat() * zoom, paint)
        }
        for (d in frame.dots) {
            paint.color = tint.color(strengthOf(d.white), hueAt(d.x - center, d.y - center, 0f), d.a.toFloat().coerceIn(0f, 1f)).toArgb()
            canvas.drawCircle(off + d.x.toFloat() * zoom, off + d.y.toFloat() * zoom, d.r.toFloat() * zoom, paint)
        }
    }
}
