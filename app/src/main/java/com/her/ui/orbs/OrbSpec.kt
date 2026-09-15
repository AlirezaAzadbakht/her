package com.her.ui.orbs

// Tunings transcribed from thinking-orbs' generated ThinkingOrbsKit/OrbSpec.swift
// (spec 1.0.0, thinking-orbs 0.3.1, Libraries.dev @ 422180dd7a5ac646c85deedc65500c4a74339127).
// Data only: retune upstream, then copy the numbers and the golden vectors together.

/** Which animation to show. */
enum class OrbState(val label: String, internal val mode: OrbMode) {
    Working("Working…", OrbMode.Orbits),
    Searching("Searching…", OrbMode.Globe),
    Solving("Solving…", OrbMode.Rubik),
    Listening("Listening…", OrbMode.Wave),
    Connecting("Connecting…", OrbMode.Web),
    Weaving("Weaving…", OrbMode.Braid),
    Composing("Composing…", OrbMode.Ribbon),
    Breathing("Thinking…", OrbMode.Ring),
    Shaping("Shaping…", OrbMode.Morph),
    ;

    val key: String get() = name.lowercase()
}

/** The two tuned presets. They are separate designs, not a scale factor. */
enum class OrbSize(val value: Int) {
    Px64(64),
    Px20(20),
}

internal enum class OrbMode {
    Orbits,
    Globe,
    Rubik,
    Wave,
    Web,
    Braid,
    Ribbon,
    Ring,
    Morph,
    ;

    val key: String get() = name.lowercase()
}

internal object OrbSpec {
    class Preset(val speed: Double, val count: Double, val size: Double, val extra: Map<String, Double> = emptyMap())

    /** Per-mode density and geometry before any preset multiplier. */
    val baseProfiles: Map<OrbMode, Map<String, Double>> = mapOf(
        OrbMode.Orbits to mapOf("orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0, "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Globe to mapOf("latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Rubik to mapOf("latRings" to 15.0, "lonDensity" to 40.0, "moveCount" to 14.0, "rBase" to 0.6, "rDepth" to 1.7, "rActive" to 0.3, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Wave to mapOf("rings" to 15.0, "lonDensity" to 40.0, "rBase" to 0.6, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Web to mapOf("nodeN" to 30.0, "thr" to 0.72, "signals" to 5.0, "nodeR" to 1.4, "nodeRDepth" to 1.8, "lineW" to 0.8, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Braid to mapOf("strandN" to 52.0, "turns" to 3.0, "ghostN" to 150.0, "rBase" to 1.2, "rDepth" to 1.8, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Ribbon to mapOf("lanes" to 5.0, "segs" to 88.0, "ghostN" to 150.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Ring to mapOf("lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
        OrbMode.Morph to mapOf("rDot" to 0.021, "iconD" to 1.0, "rMin" to 0.25),
    )

    /** Per mode and size baked tuning. */
    val presets: Map<OrbMode, Map<OrbSize, Preset>> = mapOf(
        OrbMode.Orbits to mapOf(
            OrbSize.Px64 to Preset(speed = 1.885, count = 1.0, size = 1.0),
            OrbSize.Px20 to Preset(speed = 3.9, count = 0.238, size = 2.4),
        ),
        OrbMode.Globe to mapOf(
            OrbSize.Px64 to Preset(speed = 2.015, count = 0.42, size = 1.15, extra = mapOf("scanMul" to 4.08, "dimBase" to 0.45)),
            OrbSize.Px20 to Preset(speed = 2.665, count = 0.105, size = 1.75, extra = mapOf("scanMul" to 4.335, "dimBase" to 0.45)),
        ),
        OrbMode.Rubik to mapOf(
            OrbSize.Px64 to Preset(speed = 1.82, count = 0.35, size = 1.05),
            OrbSize.Px20 to Preset(speed = 1.95, count = 0.088, size = 1.9),
        ),
        OrbMode.Wave to mapOf(
            OrbSize.Px64 to Preset(speed = 4.388, count = 0.341, size = 1.0),
            OrbSize.Px20 to Preset(speed = 3.998, count = 0.105, size = 1.6),
        ),
        OrbMode.Web to mapOf(
            OrbSize.Px64 to Preset(speed = 3.315, count = 1.35, size = 0.95),
            OrbSize.Px20 to Preset(speed = 6.63, count = 0.25, size = 1.52),
        ),
        OrbMode.Braid to mapOf(
            OrbSize.Px64 to Preset(speed = 1.625, count = 0.5, size = 1.0),
            OrbSize.Px20 to Preset(speed = 2.75, count = 0.1125, size = 1.36),
        ),
        OrbMode.Ribbon to mapOf(
            OrbSize.Px64 to Preset(speed = 2.34, count = 0.25, size = 0.85, extra = mapOf("spin" to 0.0, "bandMul" to 3.9, "wobMul" to 1.0)),
            OrbSize.Px20 to Preset(speed = 3.12, count = 0.051, size = 1.073, extra = mapOf("spin" to 0.0, "bandMul" to 4.94, "wobMul" to 1.0)),
        ),
        OrbMode.Ring to mapOf(
            OrbSize.Px64 to Preset(speed = 3.24, count = 0.25, size = 0.956, extra = mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)),
            OrbSize.Px20 to Preset(speed = 3.78, count = 0.028, size = 1.622, extra = mapOf("spin" to 0.0, "bandMul" to 3.968, "wobMul" to 0.565)),
        ),
        OrbMode.Morph to mapOf(
            OrbSize.Px64 to Preset(speed = 2.405, count = 0.702, size = 0.395, extra = mapOf("spread" to 1.45)),
            OrbSize.Px20 to Preset(speed = 2.08, count = 0.53, size = 1.011, extra = mapOf("spread" to 1.45)),
        ),
    )

    /** 2-D lattices scale by √count on each axis so the total scales by count. */
    val countPairs: List<Pair<String, String>> = listOf(
        "latRings" to "lonDensity",
        "rings" to "lonDensity",
        "lanes" to "segs",
    )
    val countKeys: List<String> = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
    val iconDensityKeys: List<String> = listOf("iconD")
    val radiusKeys: List<String> = listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth")

    /** The static instant reduced-motion users see. */
    const val REDUCED_MOTION_T: Double = 0.6
}
