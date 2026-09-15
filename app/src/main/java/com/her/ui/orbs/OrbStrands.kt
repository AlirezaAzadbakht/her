package com.her.ui.orbs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The strand modes: braid (weaving), ribbon (composing) and ring (breathing).
// Transcribed from ThinkingOrbsKit/Strands.swift.

private fun addGhostSphere(dots: MutableList<Dot>, pt: Projector, ghostN: Int, R: Double, rs: Double) {
    val d = DoubleArray(3)
    for (i in 0 until ghostN) {
        fibDir(i, ghostN, d)
        pt.project(d[0] * R, d[1] * R, d[2] * R)
        val z = pt.pz
        val depth = (z / R + 1) / 2
        dots += Dot(pt.px, pt.py, z, 0.8 * rs, 0.78, 0.1 + 0.22 * depth)
    }
}

// Braid: three strands plait around the sphere. Weaving.
internal fun frameBraid(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.76
    val pt = Projector(yaw = t * 0.4, tilt = 0.3, cx = cx, cy = cy, scale = 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val rBase = o["rBase"] ?: 1.2
    val rDepth = o["rDepth"] ?: 1.8

    val dots = ArrayList<Dot>()
    addGhostSphere(dots, pt, (o["ghostN"] ?: 150.0).toInt(), R, rs)

    val strandN = (o["strandN"] ?: 52.0).toInt()
    val turns = o["turns"] ?: 3.0
    for (s in 0 until 3) {
        val phase = (s.toDouble() / 3) * 2 * PI
        for (i in 0 until strandN) {
            // u walks pole to pole; the frac() drift slides the whole strand along
            val u = (frac(i.toDouble() / strandN + t * 0.045) * 2 - 1) * 0.96
            val surf = sqrt(max(0.0, 1 - u * u))
            val endFade = min(1.0, (1 - abs(u)) / 0.1)
            val a = u * PI * turns + phase
            // radial breathing: strands trade places, the over/under of a plait
            val weave = 1 + 0.075 * sin(u * PI * turns * 2 + phase * 2 + t * 0.8)
            val rr = surf * R * weave
            pt.project(cos(a) * rr, u * R * weave, sin(a) * rr)
            val zr = pt.pz
            val depth = (zr / R + 1) / 2
            dots += Dot(
                pt.px, pt.py, zr,
                (rBase + rDepth * depth) * rs,
                0.55 - 0.45 * depth,
                endFade * (0.45 + 0.55 * depth),
            )
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}

// Ribbon: an undulating sash orbits the sphere. Composing.
// The same geometry drives breathing (ring) through faceOn: a face-on circle whose
// radius undulates, so it reads as a ring slowly morphing rather than a sash.
internal fun frameRibbon(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.78
    // spin scales the 3D tumble; 0 freezes orientation, leaving only the undulation
    val spin = o["spin"] ?: 1.0
    val camTilt = 0.3
    val faceOn = (o["faceOn"] ?: 0.0) != 0.0
    val pt = Projector(yaw = t * 0.1 * spin, tilt = camTilt, cx = cx, cy = cy, scale = 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val rBase = o["rBase"] ?: 1.1
    val rDepth = o["rDepth"] ?: 1.7

    val dots = ArrayList<Dot>()
    val ghostN = (o["ghostN"] ?: 150.0).toInt()
    if (ghostN > 0) addGhostSphere(dots, pt, ghostN, R, rs)

    // The band plane, precessing (frozen when spin = 0). Face-on sets ta = -camTilt so the
    // projection's vertical squash is 1 and the band reads as a true circle.
    val ya = t * 0.24 * spin
    val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
    val ux = cos(ya)
    val uy = 0.0
    val uz = sin(ya)
    val vx = -uz * sin(ta)
    val vy = cos(ta)
    val vz = ux * sin(ta)
    // plane normal n = u x v
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    // Radial lobes swell past R, so pull the base radius in by most of the wobble amplitude.
    val wobMul = o["wobMul"] ?: 1.0
    val wobAmp = 0.23 * wobMul
    val baseR = if (faceOn) R / (1 + 0.85 * wobAmp) else R

    val baseLanes = o["lanes"] ?: 5.0
    val segs = (o["segs"] ?: 88.0).toInt()
    val lanes = max(1, roundPositive(baseLanes * (o["bandMul"] ?: 1.0)).toInt())
    for (w in 0 until lanes) {
        val laneOff = (w - (lanes - 1) / 2.0) * 0.075
        val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
        for (k in 0 until segs) {
            val a = (k.toDouble() / segs) * 2 * PI
            // two traveling waves along the band; wobMul 0 is a clean band
            val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * wobMul
            // A normal-direction wobble is cancelled by the re-normalisation below, so it can only
            // pull dots inward. Face-on modulates the in-plane radius instead, so lobes swell and pinch.
            val radial = if (faceOn) 1 + wob else 1.0
            val off = if (faceOn) laneOff else laneOff + wob
            val x = ux * cos(a) + vx * sin(a) + nx * off
            val y = uy * cos(a) + vy * sin(a) + ny * off
            val z = uz * cos(a) + vz * sin(a) + nz * off
            val l = sqrt(x * x + y * y + z * z)
            val rr = baseR * radial
            pt.project((x / l) * rr, (y / l) * rr, (z / l) * rr)
            val zr = pt.pz
            val depth = (zr / R + 1) / 2
            dots += Dot(
                pt.px, pt.py, zr,
                (rBase + rDepth * depth) * (1 - 0.25 * edge) * rs,
                0.52 - 0.44 * depth + 0.18 * edge,
                0.4 + 0.6 * depth,
            )
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}
