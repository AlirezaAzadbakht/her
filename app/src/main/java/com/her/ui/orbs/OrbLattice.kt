package com.her.ui.orbs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The sphere-lattice modes: globe (searching), rubik (solving) and wave (listening).
// Transcribed from ThinkingOrbsKit/Lattice.swift.

private class Move(val axis: Int, val lo: Double, val hi: Double, val ang: Double)

private class SolveCycle(val amount: DoubleArray, val active: Int)

// Rapid eased moves scramble, then replay in reverse so everything clicks back to solved.
private fun solveCycle(time: Double, count: Int, slotDur: Double, rest: Double): SolveCycle {
    val cyc = 2 * count * slotDur + rest
    val tc = time % cyc
    val amount = DoubleArray(count)
    var active = -1
    if (tc < 2 * count * slotDur) {
        val slot = floor(tc / slotDur).toInt()
        val p = (tc - slot * slotDur) / slotDur
        val cl = min(1.0, p / 0.7)
        val ep = 1 - (1 - cl).pow(3)
        if (slot < count) {
            for (i in 0 until slot) amount[i] = 1.0
            amount[slot] = ep
            active = slot
        } else {
            val u = 2 * count - 1 - slot
            for (i in 0 until u) amount[i] = 1.0
            amount[u] = 1 - ep
            active = u
        }
    }
    return SolveCycle(amount, active)
}

/** Rotates [p] in place through the active moves; returns whether it sits in the band being turned. */
private fun applyMoves(p: DoubleArray, moves: List<Move>, sc: SolveCycle): Boolean {
    var x = p[0]
    var y = p[1]
    var z = p[2]
    var inActive = false
    for (i in moves.indices) {
        if (sc.amount[i] <= 0) continue
        val mv = moves[i]
        val coord = when (mv.axis) {
            0 -> x
            1 -> y
            else -> z
        }
        if (coord < mv.lo || coord >= mv.hi) continue
        if (i == sc.active) inActive = true
        val a = mv.ang * sc.amount[i]
        val ca = cos(a)
        val sa = sin(a)
        when (mv.axis) {
            0 -> {
                val y2 = y * ca - z * sa
                z = y * sa + z * ca
                y = y2
            }
            1 -> {
                val x2 = x * ca + z * sa
                z = -x * sa + z * ca
                x = x2
            }
            else -> {
                val x2 = x * ca - y * sa
                y = x * sa + y * ca
                x = x2
            }
        }
    }
    p[0] = x
    p[1] = y
    p[2] = z
    return inActive
}

private fun makeMoves(count: Int): List<Move> = List(count) { i ->
    val axis = min(2, floor(hashD(i.toDouble(), 2.3) * 3).toInt())
    val lo = -1.0 + 0.5 * min(3, floor(hashD(i.toDouble(), 5.9) * 4).toInt())
    val dir = if (hashD(i.toDouble(), 7.7) < 0.5) 1.0 else -1.0
    Move(axis, lo, lo + 0.5, dir * PI / 2)
}

private fun lonCountFor(cosLat: Double, lonDensity: Double): Int = max(1, roundPositive(abs(cosLat) * lonDensity).toInt())

// Globe: lat/long field, a scan meridian sweeps. Searching.
internal fun frameGlobe(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val spin = 0.5
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    val tilt = 0.4 + 0.06 * sin(t * 0.35)
    val pt = Projector(yaw = t * spin, tilt = tilt, cx = cx, cy = cy, scale = radius)
    // scan sweeps relative to the spin; scanMul scales that relative rate
    val scan = t * (spin + (1.7 - spin) * (o["scanMul"] ?: 1.0))
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val dimBase = o["dimBase"] ?: 1.0
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7
    val rBoost = o["rBoost"] ?: 1.0
    val inkFar = o["inkFar"] ?: 0.62
    val inkSpan = o["inkSpan"] ?: 0.54

    val dots = ArrayList<Dot>()
    val latRings = (o["latRings"] ?: 17.0).toInt()
    val lonDensity = o["lonDensity"] ?: 44.0
    for (li in 0..latRings) {
        val lat = -PI / 2 + (li.toDouble() / latRings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = lonCountFor(cosLat, lonDensity)
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            pt.project(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            val z = pt.pz
            val depth = (z + 1) / 2
            // the scan: a moving meridian read as a size ripple, not a shine
            val d = angleDelta(lon + t * spin, scan)
            val boost = exp(-(d * d) / 0.18) * max(0.0, z)
            dots += Dot(
                pt.px, pt.py, z,
                (rBase + rDepth * depth + rBoost * boost) * rs,
                inkFar - inkSpan * depth,
                // dimBase < 1 fades un-scanned dots so the meridian reads clearly
                dimBase + (1 - dimBase) * min(1.0, boost),
            )
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}

// Rubik: bands twist in quarter turns, scramble then solve. Solving.
internal fun frameRubik(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.82
    val pt = Projector(yaw = t * 0.55, tilt = 0.35 + 0.1 * sin(t * 0.9), cx = cx, cy = cy, scale = R)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val moveCount = (o["moveCount"] ?: 14.0).toInt()
    val moves = makeMoves(moveCount)
    val sc = solveCycle(t, moveCount, 0.42, 1.2)
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7
    val rActive = o["rActive"] ?: 0.3
    val inkFar = o["inkFar"] ?: 0.62
    val inkSpan = o["inkSpan"] ?: 0.54

    val dots = ArrayList<Dot>()
    val p = DoubleArray(3)
    val latRings = (o["latRings"] ?: 15.0).toInt()
    val lonDensity = o["lonDensity"] ?: 40.0
    for (li in 0..latRings) {
        val lat = -PI / 2 + (li.toDouble() / latRings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = lonCountFor(cosLat, lonDensity)
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            p[0] = cosLat * cos(lon)
            p[1] = sinLat
            p[2] = cosLat * sin(lon)
            val inActive = applyMoves(p, moves, sc)
            pt.project(p[0], p[1], p[2])
            val zr = pt.pz
            val depth = (zr + 1) / 2
            // the band being turned inks a touch darker: the "hand"
            dots += Dot(
                pt.px, pt.py, zr,
                (rBase + rDepth * depth + (if (inActive) rActive else 0.0)) * rs,
                inkFar - inkSpan * depth - (if (inActive) 0.14 else 0.0),
            )
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}

// Wave: a waveform rolls through the rings. Listening.
internal fun frameWave(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    // 0.76 base x 1.15: the undulation pulls the sphere inward, so wave is scaled up to match the others
    val R = (size / 2) * 0.874
    val pt = Projector(yaw = t * 0.18, tilt = 0.38, cx = cx, cy = cy, scale = 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7

    val dots = ArrayList<Dot>()
    val rings = (o["rings"] ?: 15.0).toInt()
    val lonDensity = o["lonDensity"] ?: 40.0
    for (ri in 0..rings) {
        val lat = -PI / 2 + (ri.toDouble() / rings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        // two waves at different tempi: organic, never quite repeating
        val w = 0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)
        val rr = R * (0.88 + 0.105 * w)
        val lonCount = lonCountFor(cosLat, lonDensity)
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            pt.project(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
            val z = pt.pz
            val depth = (z / R + 1) / 2
            val crest = max(0.0, w)
            dots += Dot(
                pt.px, pt.py, z,
                (rBase + rDepth * depth) * (1 + 0.4 * crest) * rs,
                0.66 - 0.56 * depth - 0.1 * crest,
            )
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}
