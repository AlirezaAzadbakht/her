package com.her.scenario

import com.her.data.remote.LlmClient
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import org.json.JSONObject

data class JudgeResult(
    val passed: Boolean,
    val reason: String,
)

object Judge {
    private val system = """
        You grade whether a personal-assistant scenario succeeded.
        Read the criterion, the transcript, the tools that ran, and the database dump.
        Reply with JSON only: {"pass": true or false, "reason": "one short sentence"}.
        Be strict about the structured outcome (records created, facts stored). Be lenient about wording.
    """.trimIndent()

    suspend fun grade(
        criterion: String,
        outcome: HarnessOutcome,
        llm: LlmClient,
        settings: LlmSettings,
    ): JudgeResult {
        val judgeSettings = settings.copy(model = ScenarioEnv.judgeModel(settings.model))
        val user = buildString {
            appendLine("Criterion:")
            appendLine(criterion)
            appendLine()
            appendLine("Last reply:")
            appendLine(outcome.lastReply ?: "(none)")
            appendLine()
            appendLine("Tool calls:")
            if (outcome.toolCalls.isEmpty()) {
                appendLine("(none)")
            } else {
                outcome.toolCalls.forEach { call ->
                    appendLine("- ${call.name} ok=${call.ok} args=${call.arguments} result=${call.payloadJson}")
                }
            }
            appendLine()
            appendLine("Transcript:")
            outcome.messages.forEach { message ->
                appendLine("${message.role.name}: ${message.content}")
            }
            appendLine()
            appendLine("Database:")
            appendLine(outcome.tableDump)
        }
        return try {
            val response = llm.complete(
                settings = judgeSettings,
                messages = listOf(
                    LlmMessage(role = "system", content = system),
                    LlmMessage(role = "user", content = user),
                ),
            )
            parse(response.message.content)
        } catch (e: Exception) {
            JudgeResult(false, "judge failed: ${e.message ?: e::class.java.simpleName}")
        }
    }

    internal fun parse(raw: String?): JudgeResult {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return JudgeResult(false, "judge returned an empty reply")
        val json = extractJson(text)
            ?: return JudgeResult(false, "judge reply was not JSON: ${text.take(240)}")
        return JudgeResult(
            passed = json.optBoolean("pass", false),
            reason = json.optString("reason").ifBlank { "no reason" },
        )
    }

    private fun extractJson(text: String): JSONObject? {
        runCatching { return JSONObject(text) }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }
}
