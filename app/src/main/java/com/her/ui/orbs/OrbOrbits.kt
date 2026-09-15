package com.her.ui.orbs

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Orbits: particles on tilted orbits, the "working" state. Transcribed from
// ThinkingOrbsKit/Orbits.swift.

internal fun frameOrbits(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.82
    val pt = Projector(yaw = t * 0.12, tilt = 0.3, cx = cx, cy = cy, scale = 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)

    val orbitN = (o["orbitN"] ?: 12.0).toInt()
    val ghostN = (o["ghostN"] ?: 40.0).toInt()
    val particles = (o["particles"] ?: 3.0).toInt()
    val ghostR = o["ghostR"] ?: 0.9
    val ghostA = o["ghostA"] ?: 0.5
    val partR = o["partR"] ?: 1.2
    val partRDepth = o["partRDepth"] ?: 1.6
    val dots = ArrayList<Dot>(orbitN * (ghostN + particles))

    for (orb in 0 until orbitN) {
        val h1 = hashD(orb.toDouble(), 1.7)
        val h2 = hashD(orb.toDouble(), 5.2)
        val h3 = hashD(orb.toDouble(), 8.9)
        val ro = R * (0.45 + 0.52 * h1)
        val th = h1 * 2 * PI
        val phi = acos(2 * h2 - 1)
        // orbit plane basis (u, v perpendicular to normal n)
        val nx = sin(phi) * cos(th)
        val ny = cos(phi)
        val nz = sin(phi) * sin(th)
        var ux = -ny
        var uy = nx
        val uz = 0.0
        val ul = max(1e-6, sqrt(ux * ux + uy * uy))
        ux /= ul
        uy /= ul
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        val speed = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1.0 else -1.0)

        // ghost path
        for (k in 0 until ghostN) {
            val a = (k.toDouble() / ghostN) * 2 * PI
            pt.project(
                (ux * cos(a) + vx * sin(a)) * ro,
                (uy * cos(a) + vy * sin(a)) * ro,
                (uz * cos(a) + vz * sin(a)) * ro,
            )
            val z = pt.pz
            val depth = (z / ro + 1) / 2
            dots += Dot(pt.px, pt.py, z, ghostR * rs, 0.72, ghostA * (0.4 + 0.6 * depth))
        }
        // the particles doing the work
        for (m in 0 until particles) {
            val a = t * speed + (m.toDouble() / particles) * 2 * PI + h2 * 6
            pt.project(
                (ux * cos(a) + vx * sin(a)) * ro,
                (uy * cos(a) + vy * sin(a)) * ro,
                (uz * cos(a) + vz * sin(a)) * ro,
            )
            val z = pt.pz
            val depth = (z / ro + 1) / 2
            dots += Dot(pt.px, pt.py, z, (partR + partRDepth * depth) * rs, 0.3 - 0.22 * depth)
        }
    }
    return finalizeFrame(dots, emptyList(), o["rMin"] ?: 0.3)
}
