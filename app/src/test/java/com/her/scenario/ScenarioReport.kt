package com.her.scenario

import com.her.core.redactSecrets
import com.her.domain.MessageRole
import java.io.File

data class AttemptResult(
    val index: Int,
    val passed: Boolean,
    val checks: List<CheckResult>,
    val judge: JudgeResult?,
    val outcome: HarnessOutcome,
    val elapsedMs: Long,
)

data class ScenarioRunResult(
    val spec: ScenarioSpec,
    val attempts: List<AttemptResult>,
    val skipped: String? = null,
) {
    val passed: Boolean get() = skipped == null && attempts.any { it.passed }
    val passCount: Int get() = attempts.count { it.passed }
    val inputTokens: Long get() = attempts.sumOf { it.outcome.inputTokens }
    val outputTokens: Long get() = attempts.sumOf { it.outcome.outputTokens }
    val cachedInputTokens: Long get() = attempts.sumOf { it.outcome.cachedInputTokens }
    val elapsedMs: Long get() = attempts.sumOf { it.elapsedMs }
}

object ScenarioReport {
    fun write(results: List<ScenarioRunResult>, dir: File = ScenarioPaths.reportsDir()): File {
        dir.mkdirs()
        val index = File(dir, "index.html")
        val summary = File(dir, "summary.txt")
        results.forEach { result ->
            File(dir, "${result.spec.id}.html").writeText(scenarioPage(result))
        }
        index.writeText(indexPage(results))
        summary.writeText(summaryText(results))
        return index
    }

    private fun summaryText(results: List<ScenarioRunResult>): String = buildString {
        val passed = results.count { it.passed }
        appendLine("SCENARIOS $passed/${results.size} passed")
        results.forEach { result ->
            appendLine(
                "${mark(result)} ${result.spec.id} ${result.passCount}/${result.attempts.size} " +
                    "tokens=${result.inputTokens}+${result.outputTokens} cached=${result.cachedInputTokens} ${result.elapsedMs}ms" +
                    (result.skipped?.let { " ($it)" } ?: ""),
            )
        }
    }

    fun mark(result: ScenarioRunResult): String = statusLabel(result).uppercase()

    private fun indexPage(results: List<ScenarioRunResult>): String {
        val rows = results.joinToString("\n") { result ->
            val status = statusLabel(result)
            val klass = statusClass(result)
            """
            <tr class="$klass">
              <td><a href="${esc(result.spec.id)}.html">${esc(result.spec.id)}</a></td>
              <td>${esc(result.spec.title)}</td>
              <td>$status</td>
              <td>${result.passCount}/${result.attempts.size}</td>
              <td>${result.inputTokens + result.outputTokens} <span class="muted">(${result.cachedInputTokens} cached)</span></td>
              <td>${result.elapsedMs}</td>
            </tr>
            """.trimIndent()
        }
        val passed = results.count { it.passed }
        return page(
            "Scenario report",
            """
            <h1>Scenario report</h1>
            <p class="meta">$passed / ${results.size} passed. Pending failures do not fail the suite.</p>
            <table>
              <thead><tr><th>Id</th><th>Title</th><th>Status</th><th>Attempts</th><th>Tokens</th><th>ms</th></tr></thead>
              <tbody>
              $rows
              </tbody>
            </table>
            """.trimIndent(),
        )
    }

    private fun scenarioPage(result: ScenarioRunResult): String {
        val spec = result.spec
        val attemptsHtml = result.attempts.joinToString("\n") { attempt ->
            val checks = attempt.checks.joinToString("\n") { check ->
                val klass = if (check.passed) "ok" else "bad"
                """<p class="$klass">${esc(check.name)} — ${esc(check.detail)}</p>"""
            }
            val judge = attempt.judge?.let {
                val klass = if (it.passed) "ok" else "bad"
                """<p class="$klass">judge — ${esc(it.reason)}</p>"""
            } ?: ""
            val tools = if (attempt.outcome.toolCalls.isEmpty()) {
                "<p class=\"muted\">(none)</p>"
            } else {
                attempt.outcome.toolCalls.joinToString("\n") { call ->
                    """<pre>${esc("${call.name} ok=${call.ok}\nargs=${call.arguments}\nresult=${call.payloadJson}")}</pre>"""
                }
            }
            val transcript = attempt.outcome.messages.joinToString("\n") { message ->
                val who = if (message.role == MessageRole.USER) "YOU" else message.role.name
                """<p><strong>${esc(who)}</strong> ${esc(message.content)}</p>"""
            }
            val error = attempt.outcome.error?.let { """<p class="bad">error — ${esc(it)}</p>""" } ?: ""
            """
            <section>
              <h2>Attempt ${attempt.index + 1} — ${if (attempt.passed) "pass" else "fail"} (${attempt.elapsedMs}ms)</h2>
              $error
              <h3>Checks</h3>
              $checks
              $judge
              <h3>Reply</h3>
              <p>${esc(attempt.outcome.lastReply ?: "(none)")}</p>
              <h3>Tool calls</h3>
              $tools
              <h3>Transcript</h3>
              $transcript
              <h3>Context bundle</h3>
              <pre>${esc(attempt.outcome.systemBundle)}</pre>
              <h3>Database</h3>
              <pre>${esc(attempt.outcome.tableDump)}</pre>
            </section>
            """.trimIndent()
        }
        return page(
            spec.id,
            """
            <p><a href="index.html">All scenarios</a></p>
            <h1>${esc(spec.id)}</h1>
            <p class="meta">${esc(spec.title)} · ${statusLabel(result)} · ${result.passCount}/${result.attempts.size}</p>
            $attemptsHtml
            """.trimIndent(),
        )
    }

    private fun statusLabel(result: ScenarioRunResult): String = when {
        result.skipped != null -> "skip"
        result.passed -> "pass"
        result.spec.pending -> "pending"
        else -> "fail"
    }

    private fun statusClass(result: ScenarioRunResult): String = when {
        result.skipped != null -> "muted"
        result.passed -> "ok"
        result.spec.pending -> "pending"
        else -> "bad"
    }

    private fun page(title: String, body: String): String = redactSecrets(
        """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>${esc(title)}</title>
        <style>
          :root { color-scheme: dark; }
          body { margin:0; background:#0d0b0a; color:#f4ede4; font-family: Georgia, serif; padding:32px 28px 80px; }
          h1 { font-family: system-ui; font-size:22px; color:#e8a87c; }
          h2, h3 { font-family: system-ui; color:#e8a87c; }
          a { color:#e8a87c; }
          table { border-collapse: collapse; width:100%; max-width:960px; font-family: system-ui; }
          th, td { text-align:left; padding:8px 10px; border-bottom:1px solid #3a2d24; }
          .ok { color:#9ad4b1; }
          .bad { color:#e07a7a; }
          .pending { color:#e8c56b; }
          .meta, .muted { color:#b9a99a; font-family: system-ui; }
          pre { background:#1a1410; border:1px solid #3a2d24; padding:12px; overflow:auto; font-size:12px; }
          section { margin:32px 0; }
        </style></head><body>
        $body
        </body></html>
        """.trimIndent(),
    )

    private fun esc(value: String): String = redactSecrets(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
