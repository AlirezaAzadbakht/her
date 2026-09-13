package com.her.data.remote

import com.her.core.redactSecrets
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmToolCall
import com.her.domain.LlmUsage
import com.her.domain.ToolSpec
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class LlmException(message: String, val status: Int? = null) : IOException(message)

data class WebSearchRequest(
    val country: String? = null,
    val timezone: String? = null,
)

open class LlmClient(
    private val http: OkHttpClient = defaultClient(),
) {
    open suspend fun complete(
        settings: LlmSettings,
        messages: List<LlmMessage>,
        tools: List<ToolSpec> = emptyList(),
        client: OkHttpClient = http,
        webSearch: WebSearchRequest? = null,
    ): LlmResponse {
        if (!settings.isConfigured) {
            throw LlmException("LLM is not configured. Add a base URL, API key, and model in Settings.")
        }
        val request = request(settings, messages, tools, stream = false, webSearch = webSearch)
        val started = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val latency = System.currentTimeMillis() - started
            if (!response.isSuccessful) {
                throw LlmException(
                    redactSecrets("LLM request failed (${response.code}): ${raw.take(400)}"),
                    response.code,
                )
            }
            return parseResponse(raw, latency)
        }
    }

    open suspend fun stream(
        settings: LlmSettings,
        messages: List<LlmMessage>,
        tools: List<ToolSpec> = emptyList(),
        onDelta: (String) -> Unit,
    ): LlmResponse {
        if (!settings.isConfigured) {
            throw LlmException("LLM is not configured. Add a base URL, API key, and model in Settings.")
        }
        val request = request(settings, messages, tools, stream = true, webSearch = null)
        val started = System.currentTimeMillis()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                if (looksLikeStreamingUnsupported(response.code, raw)) {
                    return complete(settings, messages, tools).also { result ->
                        result.message.content?.takeIf { it.isNotBlank() }?.let(onDelta)
                    }
                }
                throw LlmException(
                    redactSecrets("LLM request failed (${response.code}): ${raw.take(400)}"),
                    response.code,
                )
            }
            val contentType = response.header("Content-Type").orEmpty()
            val body = response.body ?: throw LlmException("LLM stream had no body")
            if (!contentType.contains("event-stream", ignoreCase = true) &&
                !contentType.contains("text/event", ignoreCase = true)
            ) {
                val raw = body.string()
                val parsed = parseResponse(raw, System.currentTimeMillis() - started)
                parsed.message.content?.takeIf { it.isNotBlank() }?.let(onDelta)
                return parsed
            }
            val acc = StreamAccumulator()
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val data = sseData(line) ?: continue
                val result = acc.accept(data)
                result.contentDelta?.let(onDelta)
                if (!result.continueStreaming) break
            }
            return acc.toResponse(System.currentTimeMillis() - started)
        }
    }

    suspend fun ping(settings: LlmSettings): String {
        val pingClient = http.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
        val result = complete(
            settings = settings,
            messages = listOf(LlmMessage(role = "user", content = "Reply with the single word ok.")),
            client = pingClient,
        )
        return result.message.content?.trim().orEmpty().ifBlank { "ok" }
    }

    private fun request(
        settings: LlmSettings,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        stream: Boolean,
        webSearch: WebSearchRequest?,
    ): Request {
        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", messages.toJsonArray())
            .apply {
                if (webSearch != null) {
                    put("web_search_options", webSearch.toJson())
                }
                if (tools.isNotEmpty()) {
                    put(
                        "tools",
                        JSONArray().apply {
                            tools.forEach { spec ->
                                put(
                                    JSONObject()
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", spec.name)
                                                .put("description", spec.description)
                                                .put("parameters", JSONObject(spec.parametersJson)),
                                        ),
                                )
                            }
                        },
                    )
                    put("tool_choice", "auto")
                }
                if (stream) {
                    put("stream", true)
                    put("stream_options", JSONObject().put("include_usage", true))
                }
            }
        return Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    private fun parseResponse(raw: String, latency: Long): LlmResponse {
        val root = JSONObject(raw)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException("LLM response had no choices")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val toolCalls = message.optJSONArray("tool_calls")
        val parsedCalls = if (toolCalls == null) {
            null
        } else {
            buildList<LlmToolCall> {
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: JSONObject()
                    add(
                        LlmToolCall(
                            id = call.optString("id", "call_$i"),
                            name = fn.optString("name"),
                            arguments = fn.optString("arguments", "{}"),
                        ),
                    )
                }
            }
        }
        val usage = root.optJSONObject("usage")
        return LlmResponse(
            message = LlmMessage(
                role = message.optString("role", "assistant"),
                content = message.optString("content").takeIf { it.isNotBlank() },
                toolCalls = parsedCalls,
            ),
            usage = LlmUsage(
                inputTokens = usage?.optInt("prompt_tokens") ?: 0,
                outputTokens = usage?.optInt("completion_tokens") ?: 0,
                latencyMs = latency,
            ),
            rawJson = raw,
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        internal fun looksLikeStreamingUnsupported(status: Int, raw: String): Boolean {
            val lower = raw.lowercase()
            if ("stream" !in lower) return false
            return status in setOf(400, 404, 422) ||
                "not support" in lower ||
                "unsupported" in lower ||
                "disabled" in lower
        }
    }
}

private fun WebSearchRequest.toJson(): JSONObject {
    val options = JSONObject()
    val country = country?.trim().orEmpty()
    val timezone = timezone?.trim().orEmpty()
    if (country.isNotBlank() || timezone.isNotBlank()) {
        options.put(
            "user_location",
            JSONObject().apply {
                put("type", "approximate")
                if (country.isNotBlank()) put("country", country)
                if (timezone.isNotBlank()) put("timezone", timezone)
            },
        )
    }
    return options
}

private fun List<LlmMessage>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { message ->
        val obj = JSONObject().put("role", message.role)
        if (message.content != null) obj.put("content", message.content)
        if (message.toolCallId != null) obj.put("tool_call_id", message.toolCallId)
        if (message.name != null) obj.put("name", message.name)
        message.toolCalls?.let { calls ->
            obj.put(
                "tool_calls",
                JSONArray().apply {
                    calls.forEach { call ->
                        put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", call.name).put("arguments", call.arguments),
                                ),
                        )
                    }
                },
            )
        }
        array.put(obj)
    }
    return array
}
