package com.her.data.remote

import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmToolCall
import com.her.domain.LlmUsage
import org.json.JSONObject

data class AcceptResult(
    val continueStreaming: Boolean,
    val contentDelta: String?,
)

class StreamAccumulator {
    private val content = StringBuilder()
    private val tools = sortedMapOf<Int, ToolAcc>()
    private var role: String = "assistant"
    private var usage: LlmUsage = LlmUsage(0, 0, 0)
    private var lastRaw: String = ""

    fun accept(data: String): AcceptResult {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return AcceptResult(true, null)
        if (trimmed == "[DONE]") return AcceptResult(false, null)
        lastRaw = trimmed
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            return AcceptResult(true, null)
        }
        val usageObj = root.optJSONObject("usage")
        if (usageObj != null) {
            usage = LlmUsage(
                inputTokens = usageObj.optInt("prompt_tokens"),
                outputTokens = usageObj.optInt("completion_tokens"),
                latencyMs = 0,
            )
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: return AcceptResult(true, null)
        val delta = when {
            choice.has("delta") -> choice.optJSONObject("delta") ?: JSONObject()
            choice.has("message") -> choice.optJSONObject("message") ?: JSONObject()
            else -> JSONObject()
        }
        if (delta.has("role") && delta.optString("role").isNotBlank()) {
            role = delta.optString("role")
        }
        var fragment: String? = null
        if (delta.has("content") && !delta.isNull("content")) {
            val piece = delta.optString("content")
            if (piece.isNotEmpty()) {
                content.append(piece)
                fragment = piece
            }
        }
        val calls = delta.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val index = call.optInt("index", i)
                val acc = tools.getOrPut(index) { ToolAcc() }
                if (call.has("id") && call.optString("id").isNotBlank()) {
                    acc.id = call.optString("id")
                }
                val fn = call.optJSONObject("function")
                if (fn != null) {
                    if (fn.has("name") && fn.optString("name").isNotBlank()) {
                        acc.name = fn.optString("name")
                    }
                    if (fn.has("arguments") && !fn.isNull("arguments")) {
                        acc.arguments.append(fn.optString("arguments"))
                    }
                }
            }
        }
        return AcceptResult(true, fragment)
    }

    fun toResponse(latency: Long): LlmResponse {
        val toolCalls = tools.entries.map { (index, acc) ->
            LlmToolCall(
                id = acc.id ?: "call_$index",
                name = acc.name.orEmpty(),
                arguments = acc.arguments.toString().ifBlank { "{}" },
            )
        }.takeIf { it.isNotEmpty() }
        return LlmResponse(
            message = LlmMessage(
                role = role,
                content = content.toString().takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
            ),
            usage = usage.copy(latencyMs = latency),
            rawJson = lastRaw,
        )
    }

    private class ToolAcc {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}

fun sseData(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
    if (!trimmed.startsWith("data:")) return null
    return trimmed.removePrefix("data:").trimStart()
}
