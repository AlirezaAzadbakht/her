package com.her

import android.app.Application
import com.her.ui.orbs.OrbSize
import com.her.ui.orbs.OrbState
import com.her.ui.orbs.orbFrame
import com.her.ui.orbs.resolvePreset
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Kotlin orb engine must reproduce thinking-orbs' geometry against its golden vectors.
 *
 * Dots are compared as a multiset plus a far-to-near check, not by position: `breathing`
 * at 64pt has a centre lane whose z cancels to ±1e-17 noise, so libm decides how that tie
 * group sorts. Every dot in it has identical radius, ink and alpha, so order cannot change
 * a pixel. Lines are compared by position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class OrbGoldenTest {
    private val golden: JSONObject by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("thinking-orbs/orbs-golden.json")) {
            "missing thinking-orbs/orbs-golden.json test resource"
        }
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun resolvedPresetsMatch() {
        val tol = golden.getDouble("tolerance")
        val resolved = golden.getJSONObject("resolved")
        val failures = mutableListOf<String>()
        for (state in OrbState.entries) {
            for (size in OrbSize.entries) {
                val key = "${state.key}-${size.value}"
                val expected = resolved.getJSONObject(key)
                val preset = resolvePreset(state, size)
                if (abs(preset.speed - expected.getDouble("speed")) > tol) failures += "$key: speed ${preset.speed}"
                val opts = expected.getJSONObject("opts")
                val names = opts.keys().asSequence().toSet()
                val actual = preset.opts
                if (actual.keys != names) failures += "$key: opts keys ${actual.keys.sorted()} vs ${names.sorted()}"
                for (name in names) {
                    val value = actual[name] ?: continue
                    if (abs(value - opts.getDouble(name)) > tol) failures += "$key.$name: got $value, expected ${opts.getDouble(name)}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun framesMatchGoldenVectors() {
        val tol = golden.getDouble("tolerance")
        val cases = golden.getJSONArray("cases")
        val failures = mutableListOf<String>()
        var checked = 0

        fun cmp(label: String, actual: Double, expected: Double) {
            checked += 1
            if (abs(actual - expected) > tol && failures.size < 25) failures += "$label: got $actual, expected $expected"
        }

        for (ci in 0 until cases.length()) {
            val c = cases.getJSONObject(ci)
            val key = c.getString("key")
            val state = OrbState.entries.firstOrNull { it.key == c.getString("state") }
            val size = OrbSize.entries.firstOrNull { it.value == c.getInt("size") }
            if (state == null || size == null) {
                failures += "$key: unknown state/size"
                continue
            }
            val preset = resolvePreset(state, size)
            if (preset.mode.key != c.getString("mode")) failures += "$key: mode ${preset.mode.key}"

            val frame = orbFrame(preset, size.value.toDouble(), c.getDouble("t"))
            val dotCount = c.getInt("dotCount")
            val lineCount = c.getInt("lineCount")
            if (frame.dots.size != dotCount || frame.lines.size != lineCount) {
                failures += "$key: ${frame.dots.size}/${frame.lines.size} dots/lines, expected $dotCount/$lineCount"
                continue
            }

            // Round both sides to the golden file's 6 decimals before sorting, so near-ties sort alike.
            val q6 = { v: Double -> Math.round(v * 1_000_000) / 1_000_000.0 }
            val lex = Comparator<DoubleArray> { p, q ->
                for (i in p.indices) {
                    if (p[i] < q[i]) return@Comparator -1
                    if (p[i] > q[i]) return@Comparator 1
                }
                0
            }
            val mine = frame.dots
                .map { d -> doubleArrayOf(q6(d.x), q6(d.y), q6(d.z), q6(d.r), q6(d.white), q6(d.a)) }
                .sortedWith(lex)
            val dots = c.getJSONArray("dots")
            val theirs = List(dotCount) { i -> DoubleArray(6) { f -> q6(dots.getDouble(i * 6 + f)) } }
                .sortedWith(lex)
            val fields = listOf("x", "y", "z", "r", "white", "a")
            for (i in mine.indices) {
                for (f in 0 until 6) cmp("$key dot$i.${fields[f]}", mine[i][f], theirs[i][f])
            }

            for (i in 1 until frame.dots.size) {
                if (frame.dots[i].z < frame.dots[i - 1].z) {
                    failures += "$key: dots not z-sorted at $i"
                    break
                }
            }

            val lines = c.getJSONArray("lines")
            frame.lines.forEachIndexed { i, l ->
                val b = i * 7
                cmp("$key line$i.x1", l.x1, lines.getDouble(b))
                cmp("$key line$i.y1", l.y1, lines.getDouble(b + 1))
                cmp("$key line$i.x2", l.x2, lines.getDouble(b + 2))
                cmp("$key line$i.y2", l.y2, lines.getDouble(b + 3))
                cmp("$key line$i.white", l.white, lines.getDouble(b + 4))
                cmp("$key line$i.a", l.a, lines.getDouble(b + 5))
                cmp("$key line$i.w", l.w, lines.getDouble(b + 6))
            }
        }

        assertTrue("${failures.size} mismatch(es) of $checked values:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertTrue("expected 72 golden cases, got ${cases.length()}", cases.length() == 72)
        println("golden: ${cases.length()} cases, $checked values within $tol (spec ${golden.getString("specVersion")})")
    }
}
