package com.her.scenario

import android.app.Application
import com.her.data.remote.LlmClient
import com.her.data.secure.LlmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ScenarioEngineTest {
    @Test
    fun runScenarioPool() {
        val settings = ScenarioEnv.load()
        val llm = LlmClient()
        val requestedAttempts = intProp("scenario.attempts")
        val parallel = (intProp("scenario.parallel") ?: 4).coerceAtLeast(1)
        val only = stringProp("scenario.only")
        val filter = stringProp("scenario.filter")
        val pool = ScenarioLoader.loadPool().filter { spec ->
            when {
                only != null -> spec.id == only
                filter != null -> {
                    val needle = filter.lowercase()
                    spec.id.contains(needle) ||
                        spec.title.lowercase().contains(needle) ||
                        spec.tags.any { it.lowercase().contains(needle) }
                }
                else -> true
            }
        }
        if (only != null && pool.isEmpty()) {
            error("No scenario with id '$only'")
        }
        if (pool.isEmpty()) {
            error("No scenarios matched the filter")
        }

        val results = runBlocking {
            val gate = Semaphore(parallel)
            coroutineScope {
                pool.map { spec ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runScenario(spec, settings, llm, requestedAttempts)
                        }
                    }
                }.awaitAll()
            }
        }

        val report = ScenarioReport.write(results)
        val summary = results.joinToString("\n") { result ->
            val mark = if (result.passed) "PASS" else if (result.spec.pending) "PENDING" else "FAIL"
            "$mark ${result.spec.id} ${result.passCount}/${result.attempts.size}"
        }
        println(summary)
        println("Scenario report: ${report.absolutePath}")
        val failures = results.filter { !it.passed && !it.spec.pending }
        assertTrue(
            "Failed scenarios:\n${failures.joinToString("\n") { it.spec.id }}\nSee ${report.absolutePath}",
            failures.isEmpty(),
        )
    }

    private suspend fun runScenario(
        spec: ScenarioSpec,
        settings: LlmSettings,
        llm: LlmClient,
        attemptsOverride: Int?,
    ): ScenarioRunResult {
        val attempts = (attemptsOverride ?: spec.attempts).coerceAtLeast(1)
        val outcomes = (0 until attempts).map { index ->
            val started = System.currentTimeMillis()
            val harness = ScenarioHarness(spec, settings, llm, index)
            try {
                val outcome = harness.run()
                val checks = if (outcome.error == null) {
                    Checks.evaluate(spec, harness.repo, outcome.toolCalls, outcome.lastReply)
                } else {
                    emptyList()
                }
                val checksPass = outcome.error == null && checks.all { it.passed }
                val judge = if (checksPass && spec.expect.reply?.judge != null) {
                    Judge.grade(spec.expect.reply.judge, outcome, llm, settings)
                } else {
                    null
                }
                val passed = checksPass && (judge == null || judge.passed)
                AttemptResult(
                    index = index,
                    passed = passed,
                    checks = checks,
                    judge = judge,
                    outcome = outcome,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            } finally {
                harness.close()
            }
        }
        return ScenarioRunResult(spec, outcomes)
    }

    private fun stringProp(name: String): String? =
        System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun intProp(name: String): Int? = stringProp(name)?.toIntOrNull()
}
