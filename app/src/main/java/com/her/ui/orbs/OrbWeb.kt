package com.her.ui.orbs

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Web: a constellation wires itself, the "connecting" state. Nodes drift on the sphere
// under slow value noise; close pairs grow an edge, and bright packets run between
// re-picked node pairs. Transcribed from ThinkingOrbsKit/Web.swift.

internal fun frameWeb(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.8 * (o["spread"] ?: 1.0)
    // the projector carries the radius, so node vectors stay unit-length
    val pt = Projector(yaw = t * 0.12, tilt = 0.32, cx = cx, cy = cy, scale = R)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)

    val nodeN = (o["nodeN"] ?: 30.0).toInt()
    val thr = o["thr"] ?: 0.72
    val nodeR = o["nodeR"] ?: 1.4
    val nodeRDepth = o["nodeRDepth"] ?: 1.8
    val lineW = max(0.6, (o["lineW"] ?: 0.8) * rs)

    // nodes: fib lattice plus slow noise wander, renormalised to the surface
    val nx = DoubleArray(nodeN)
    val ny = DoubleArray(nodeN)
    val nz = DoubleArray(nodeN)
    val d = DoubleArray(3)
    for (i in 0 until nodeN) {
        fibDir(i, nodeN, d)
        val x = d[0] + 0.3 * (vnoise(i * 0.31 + 9, t * 0.24) - 0.5) * 2
        val y = d[1] + 0.3 * (vnoise(i * 0.53 + 27, t * 0.21) - 0.5) * 2
        val z = d[2] + 0.3 * (vnoise(i * 0.77 + 55, t * 0.27) - 0.5) * 2
        val l = sqrt(x * x + y * y + z * z)
        nx[i] = x / l
        ny[i] = y / l
        nz[i] = z / l
    }

    val lines = ArrayList<OrbLine>()
    val dots = ArrayList<Dot>(nodeN + 8)

    // edges between close neighbours, alpha by proximity and depth
    for (i in 0 until nodeN) {
        for (j in i + 1 until nodeN) {
            val dx = nx[i] - nx[j]
            val dy = ny[i] - ny[j]
            val dz = nz[i] - nz[j]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist >= thr) continue
            pt.project(nx[i], ny[i], nz[i])
            val x1 = pt.px
            val y1 = pt.py
            val z1 = pt.pz
            pt.project(nx[j], ny[j], nz[j])
            val depth = ((z1 + pt.pz) / 2 + 1) / 2
            lines += OrbLine(x1, y1, pt.px, pt.py, 0.42, (1 - dist / thr) * (0.3 + 0.55 * depth), lineW)
        }
    }

    for (i in 0 until nodeN) {
        pt.project(nx[i], ny[i], nz[i])
        val z = pt.pz
        val depth = (z + 1) / 2
        val pulse = 1 + 0.25 * sin(t * 1.4 + i * 2.7)
        dots += Dot(pt.px, pt.py, z, (nodeR + nodeRDepth * depth) * pulse * rs, 0.55 - 0.45 * depth)
    }

    // signals: bright packets running between paired nodes
    val signals = (o["signals"] ?: 5.0).toInt()
    for (s in 0 until signals) {
        val seg = floor(t * 0.55 + s * 7.31)
        val a = floor(hashD(seg, s * 3.1 + 1.7) * nodeN).toInt()
        val b = floor(hashD(seg, s * 5.7 + 4.2) * nodeN).toInt()
        if (a == b) continue
        val f = frac(t * 0.55 + s * 7.31)
        val x = lerp(nx[a], nx[b], f)
        val y = lerp(ny[a], ny[b], f)
        val z = lerp(nz[a], nz[b], f)
        val l = max(1e-6, sqrt(x * x + y * y + z * z))
        pt.project(x / l, y / l, z / l)
        val zr = pt.pz
        val depth = (zr + 1) / 2
        dots += Dot(pt.px, pt.py, zr, (nodeR * 1.5 + nodeRDepth * depth) * rs, 0.05, 0.5 + 0.5 * depth)
    }

    return finalizeFrame(dots, lines, o["rMin"] ?: 0.3)
}
