package com.her.ui.orbs

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

// Resolves a state and size to its mode and fully scaled options. Transcribed from
// ThinkingOrbsKit/Presets.swift; the numbers live in OrbSpec.

class ResolvedPreset internal constructor(
    internal val mode: OrbMode,
    val speed: Double,
    internal val opts: Map<String, Double>,
)

private fun scaleCounts(opts: Map<String, Double>, scale: Double): Map<String, Double> {
    val out = opts.toMutableMap()
    val done = mutableSetOf<String>()
    val rt = sqrt(scale)
    for ((a, b) in OrbSpec.countPairs) {
        val va = out[a]
        val vb = out[b]
        if (va != null && vb != null && a !in done && b !in done) {
            out[a] = max(2.0, roundPositive(va * rt))
            out[b] = max(2.0, roundPositive(vb * rt))
            done += a
            done += b
        }
    }
    for (k in OrbSpec.countKeys) {
        // 0 means the mode opted out of that layer; scaling must not resurrect it as one stray dot.
        val v = out[k]
        if (v != null && v != 0.0 && k !in done) {
            out[k] = max(1.0, roundPositive(v * scale))
        }
    }
    for (k in OrbSpec.iconDensityKeys) {
        val v = out[k]
        if (v != null) out[k] = max(0.02, v * scale)
    }
    return out
}

private fun scaleRadii(opts: Map<String, Double>, scale: Double): Map<String, Double> {
    val out = opts.toMutableMap()
    for (k in OrbSpec.radiusKeys) {
        val v = out[k]
        if (v != null) out[k] = v * scale
    }
    out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
    return out
}

private val presetCache = ConcurrentHashMap<String, ResolvedPreset>()

/** Cached for the life of the process; the render loop never repeats this work. */
fun resolvePreset(state: OrbState, size: OrbSize): ResolvedPreset =
    presetCache.getOrPut("${state.key}-${size.value}") {
        val mode = state.mode
        val preset = OrbSpec.presets.getValue(mode).getValue(size)
        var opts = OrbSpec.baseProfiles.getValue(mode)
        if (preset.count != 1.0) opts = scaleCounts(opts, preset.count)
        if (preset.size != 1.0) opts = scaleRadii(opts, preset.size)
        if (preset.extra.isNotEmpty()) opts = opts + preset.extra
        ResolvedPreset(mode, preset.speed, opts)
    }

/** Geometry for one instant at [size] points and engine time [t]. */
fun orbFrame(preset: ResolvedPreset, size: Double, t: Double): OrbFrame {
    val o = preset.opts
    return when (preset.mode) {
        OrbMode.Orbits -> frameOrbits(size, t, o)
        OrbMode.Globe -> frameGlobe(size, t, o)
        OrbMode.Rubik -> frameRubik(size, t, o)
        OrbMode.Wave -> frameWave(size, t, o)
        OrbMode.Web -> frameWeb(size, t, o)
        OrbMode.Braid -> frameBraid(size, t, o)
        // ring shares ribbon's geometry; the faceOn flag switches it
        OrbMode.Ribbon, OrbMode.Ring -> frameRibbon(size, t, o)
        OrbMode.Morph -> frameMorph(size, t, o)
    }
}
