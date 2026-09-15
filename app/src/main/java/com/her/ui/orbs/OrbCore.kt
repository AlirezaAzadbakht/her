package com.her.ui.orbs

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Shared primitives for the dotted thought-orbs, transcribed from thinking-orbs'
// ThinkingOrbsKit/Core.swift (see third_party/thinking-orbs). Every formula must
// match upstream: OrbGoldenTest compares this engine against the upstream golden
// vectors dot by dot. Doubles throughout, because the vectors come from JS numbers.

/** One mark. `white` is ink: 0 = darkest ink on paper, mirrored on dark themes. */
class Dot(
    val x: Double,
    val y: Double,
    val z: Double,
    var r: Double,
    val white: Double,
    val a: Double = 1.0,
)

/** A stroked edge between two projected points (the `connecting` web). */
class OrbLine(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val white: Double,
    val a: Double,
    val w: Double,
)

/** One instant: dots already z-sorted far to near and radius-clamped; lines draw first. */
class OrbFrame(val dots: List<Dot>, val lines: List<OrbLine>)

/** Deterministic hash in [0, 1). */
internal fun hashD(a: Double, b: Double): Double {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}

/** Value noise on a 2D lattice. */
internal fun vnoise(x: Double, y: Double): Double {
    val xi = floor(x)
    val yi = floor(y)
    var fx = x - xi
    var fy = y - yi
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    val a = hashD(xi, yi)
    val b = hashD(xi + 1, yi)
    val c = hashD(xi, yi + 1)
    val d = hashD(xi + 1, yi + 1)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
}

/** Stable direction on a unit sphere (Fibonacci lattice), written into [out]. */
internal fun fibDir(i: Int, n: Int, out: DoubleArray) {
    val golden = PI * (3 - sqrt(5.0))
    val y = 1 - (2 * (i + 0.5)) / n
    val rad = sqrt(1 - y * y)
    val a = i * golden
    out[0] = rad * cos(a)
    out[1] = y
    out[2] = rad * sin(a)
}

/** Shortest signed angular distance, wrapped to (-π, π]. */
internal fun angleDelta(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

internal fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

internal fun frac(x: Double): Double = x - floor(x)

/** Upstream rounds half away from zero; every rounded value here is positive, so half-up matches. */
internal fun roundPositive(x: Double): Double = Math.round(x).toDouble()

/** Dot radii were tuned for a 300pt frame; sub-linear scaling keeps small orbs legible. */
internal fun radiusScale(size: Double, p: Double): Double = (size / 300).pow(p)

/** Shared spin + tilt + orthographic projection; results land in [px], [py], [pz] so loops don't allocate. */
internal class Projector(yaw: Double, tilt: Double, private val cx: Double, private val cy: Double, private val scale: Double) {
    private val st = sin(tilt)
    private val ct = cos(tilt)
    private val sy = sin(yaw)
    private val cyw = cos(yaw)

    var px = 0.0
        private set
    var py = 0.0
        private set
    var pz = 0.0
        private set

    fun project(x: Double, y: Double, z: Double) {
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        px = cx + x1 * scale
        py = cy - y1 * scale
        pz = z2
    }
}

/**
 * Drops invisible marks, clamps radii to the mode's floor, and z-sorts far to near.
 * The comparator uses primitive `<` so -0.0 and 0.0 tie as they do upstream, and
 * `sortedWith` is stable, which keeps co-planar dots in emission order like JS.
 */
internal fun finalizeFrame(dots: List<Dot>, lines: List<OrbLine>, rMin: Double = 0.3): OrbFrame {
    val visible = ArrayList<Dot>(dots.size)
    for (d in dots) {
        if (d.a >= 0.02) {
            d.r = max(rMin, d.r)
            visible.add(d)
        }
    }
    val sorted = visible.sortedWith { p, q ->
        when {
            p.z < q.z -> -1
            p.z > q.z -> 1
            else -> 0
        }
    }
    return OrbFrame(sorted, lines.filter { it.a >= 0.02 })
}
