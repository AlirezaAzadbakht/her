package com.her.ui.orbs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Morph: a dotted outline cycling circle, triangle, square. The "shaping" state.
// Each shape is a closed path parameterised by arc length (top-centre start, clockwise).
// Every frame blends the neighbouring paths, then lays dots evenly along the blend.
// Transcribed from ThinkingOrbsKit/Morph.swift.

private fun smoothE(x: Double): Double = x * x * (3 - 2 * x)

private fun interface Shape {
    /** Writes the point at arc fraction [f] into [out]. */
    fun point(f: Double, out: DoubleArray)
}

private class PolyPath(private val xs: DoubleArray, private val ys: DoubleArray) : Shape {
    private val segLengths = DoubleArray(xs.size)
    private val total: Double

    init {
        var sum = 0.0
        for (i in xs.indices) {
            val j = (i + 1) % xs.size
            val l = sqrt((xs[j] - xs[i]) * (xs[j] - xs[i]) + (ys[j] - ys[i]) * (ys[j] - ys[i]))
            segLengths[i] = l
            sum += l
        }
        total = sum
    }

    override fun point(f: Double, out: DoubleArray) {
        var target = f * total
        var i = 0
        while (target > segLengths[i] && i < xs.size - 1) {
            target -= segLengths[i]
            i += 1
        }
        val j = (i + 1) % xs.size
        val ff = if (segLengths[i] != 0.0) min(1.0, target / segLengths[i]) else 0.0
        out[0] = xs[i] + (xs[j] - xs[i]) * ff
        out[1] = ys[i] + (ys[j] - ys[i]) * ff
    }
}

private val Circle = Shape { f, out ->
    val a = -PI / 2 + f * 2 * PI
    out[0] = cos(a) * 0.24
    out[1] = sin(a) * 0.24
}

private val Triangle = PolyPath(doubleArrayOf(0.0, 0.24, -0.24), doubleArrayOf(-0.26, 0.16, 0.16))

// 5-vertex walk so the path starts at top-centre like the other shapes
private val Square = PolyPath(doubleArrayOf(0.0, 0.2, 0.2, -0.2, -0.2), doubleArrayOf(-0.2, -0.2, 0.2, 0.2, -0.2))

private val Cycle: List<Shape> = listOf(Circle, Triangle, Square)

// low floor keeps sparse outlines possible while never degenerating
private fun morphN(d: Double): Int = max(6, roundPositive(34 * d).toInt())

private const val Hold = 1.4
private const val MorphDur = 0.9
private const val Seg = Hold + MorphDur
private const val Samples = 160

internal fun frameMorph(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val kCount = Cycle.size
    val tc = t % (Seg * kCount)
    val k = floor(tc / Seg).toInt()
    val local = tc - k * Seg
    val m = if (local > Hold) smoothE((local - Hold) / MorphDur) else 0.0
    val sprd = o["spread"] ?: 1.0

    // blend the two shape paths at m, then measure the blended outline
    val pA = Cycle[k]
    val pB = Cycle[(k + 1) % kCount]
    val px = DoubleArray(Samples)
    val py = DoubleArray(Samples)
    val a = DoubleArray(2)
    val b = DoubleArray(2)
    for (i in 0 until Samples) {
        val f = i.toDouble() / Samples
        pA.point(f, a)
        pB.point(f, b)
        px[i] = (a[0] + (b[0] - a[0]) * m) * sprd
        py[i] = (a[1] + (b[1] - a[1]) * m) * sprd
    }
    val lens = DoubleArray(Samples)
    var total = 0.0
    for (i in 0 until Samples) {
        val j = (i + 1) % Samples
        val l = sqrt((px[j] - px[i]) * (px[j] - px[i]) + (py[j] - py[i]) * (py[j] - py[i]))
        lens[i] = l
        total += l
    }

    // dot radius depends only on rDot; the count sets the gaps. Formed shapes breathe a little.
    val n = morphN(o["iconD"] ?: 1.0)
    val re = (o["rDot"] ?: 0.021) * 1.35 * sprd
    val pulse = 1 + 0.02 * sin(local * 3.1)

    val dots = ArrayList<Dot>(n)
    val c2 = size / 2
    var seg = 0
    var acc = 0.0
    for (k2 in 0 until n) {
        val target = (k2.toDouble() / n) * total
        while (acc + lens[seg] < target && seg < Samples - 1) {
            acc += lens[seg]
            seg += 1
        }
        val j = (seg + 1) % Samples
        val f = if (lens[seg] != 0.0) min(1.0, (target - acc) / lens[seg]) else 0.0
        val x = (px[seg] + (px[j] - px[seg]) * f) * pulse
        val y = (py[seg] + (py[j] - py[seg]) * f) * pulse
        dots += Dot(c2 + x * size, c2 + y * size, 0.0, max(0.35, re * size), 0.1)
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}
