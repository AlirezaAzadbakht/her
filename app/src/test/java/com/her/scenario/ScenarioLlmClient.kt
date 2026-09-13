package com.her.scenario

import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchRequest
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmToolCall
import com.her.domain.LlmUsage
import com.her.domain.ToolSpec
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

enum class ScenarioMode {
    LIVE,
    RECORD,
    REPLAY,
    ;

    companion object {
        fun parse(raw: String?): ScenarioMode = when (raw?.trim()?.lowercase()) {
            null, "", "live" -> LIVE
            "record" -> RECORD
            "replay" -> REPLAY
            else -> error("scenario.mode must be live, record, or replay (got '$raw')")
        }
    }
}

class CassetteMismatchException(message: String) : IllegalStateException(message)

data class CassetteCall(
    val fingerprint: String,
    val summary: String,
    val response: LlmResponse,
)

class CassetteAttempt(
    val startMillis: Long,
    val calls: MutableList<CassetteCall> = mutableListOf(),
)

class Cassette(
    val id: String,
    val model: String,
    val attempts: MutableList<CassetteAttempt> = mutableListOf(),
) {
    fun save(dir: File = ScenarioPaths.cassettesDir()) {
        dir.mkdirs()
        val root = JSONObject()
            .put("id", id)
            .put("model", model)
            .put(
                "attempts",
                JSONArray().apply {
                    attempts.forEach { attempt ->
                        put(
                            JSONObject()
                                .put("startMillis", attempt.startMillis)
                                .put("calls", JSONArray().apply { attempt.calls.forEach { put(it.toJson()) } }),
                        )
                    }
                },
            )
        File(dir, "$id.json").writeText(root.toString(2) + "\n")
    }

    companion object {
        fun load(id: String, dir: File = ScenarioPaths.cassettesDir()): Cassette? {
            val file = File(dir, "$id.json")
            if (!file.isFile) return null
            val root = JSONObject(file.readText())
            val attempts = root.getJSONArray("attempts")
            return Cassette(
                id = root.getString("id"),
                model = root.optString("model"),
                attempts = (0 until attempts.length()).map { index ->
                    val attempt = attempts.getJSONObject(index)
                    val calls = attempt.getJSONArray("calls")
                    CassetteAttempt(
                        startMillis = attempt.getLong("startMillis"),
                        calls = (0 until calls.length()).map { callFromJson(calls.getJSONObject(it)) }.toMutableList(),
                    )
                }.toMutableList(),
            )
        }

        private fun callFromJson(obj: JSONObject): CassetteCall {
            val response = obj.getJSONObject("response")
            val toolCalls = response.optJSONArray("toolCalls")?.let { array ->
                (0 until array.length()).map { i ->
                    val call = array.getJSONObject(i)
                    LlmToolCall(call.getString("id"), call.getString("name"), call.getString("arguments"))
                }
            }
            val usage = response.optJSONObject("usage") ?: JSONObject()
            return CassetteCall(
                fingerprint = obj.getString("fingerprint"),
                summary = obj.optString("summary"),
                response = LlmResponse(
                    message = LlmMessage(
                        role = response.optString("role", "assistant"),
                        content = if (response.isNull("content")) null else response.optString("content"),
                        toolCalls = toolCalls,
                    ),
                    usage = LlmUsage(usage.optInt("input"), usage.optInt("output"), 0),
                    rawJson = "{\"replayed\":true}",
                ),
            )
        }

        private fun CassetteCall.toJson(): JSONObject = JSONObject()
            .put("fingerprint", fingerprint)
            .put("summary", summary)
            .put(
                "response",
                JSONObject()
                    .put("role", response.message.role)
                    .put("content", response.message.content ?: JSONObject.NULL)
                    .apply {
                        response.message.toolCalls?.let { calls ->
                            put(
                                "toolCalls",
                                JSONArray().apply {
                                    calls.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("arguments", it.arguments)) }
                                },
                            )
                        }
                    }
                    .put("usage", JSONObject().put("input", response.usage.inputTokens).put("output", response.usage.outputTokens)),
            )
    }
}

/**
 * Wraps the LLM for one scenario attempt: counts calls and tokens, and records or replays responses.
 *
 * The fingerprint covers what a code change can break deterministically: the tool schemas, the
 * message roles, tool-call names, and the user's words. It skips system text and tool payloads,
 * which carry the current time and random ids.
 */
class ScenarioLlmClient(
    private val mode: ScenarioMode,
    private val delegate: LlmClient?,
    val tape: CassetteAttempt,
) : LlmClient() {
    var calls: Int = 0
        private set
    var inputTokens: Long = 0
        private set
    var outputTokens: Long = 0
        private set
    var cachedInputTokens: Long = 0
        private set

    override suspend fun complete(
        settings: LlmSettings,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        client: OkHttpClient,
        webSearch: WebSearchRequest?,
    ): LlmResponse = exchange(messages, tools, onReplay = {}) {
        requireDelegate().complete(settings, messages, tools, webSearch = webSearch)
    }

    override suspend fun stream(
        settings: LlmSettings,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
    ): LlmResponse = exchange(messages, tools, onReplay = { response -> response.message.content?.let(onDelta) }) {
        requireDelegate().stream(settings, messages, tools, onDelta)
    }

    private suspend fun exchange(
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onReplay: (LlmResponse) -> Unit,
        live: suspend () -> LlmResponse,
    ): LlmResponse {
        val index = calls
        calls += 1
        val print = fingerprint(messages, tools)
        val summary = summarize(messages, tools)
        val response = when (mode) {
            ScenarioMode.REPLAY -> {
                val recorded = tape.calls.getOrNull(index)
                    ?: throw CassetteMismatchException(
                        "cassette ended at call ${index + 1}; the agent now makes more calls. Now: $summary. " +
                            "Re-record with -Pscenario.mode=record.",
                    )
                if (recorded.fingerprint != print) {
                    throw CassetteMismatchException(
                        "request drifted at call ${index + 1}.\n  recorded: ${recorded.summary}\n  now:      $summary\n" +
                            "Re-record with -Pscenario.mode=record.",
                    )
                }
                recorded.response.also(onReplay)
            }
            ScenarioMode.RECORD -> live().also { tape.calls += CassetteCall(print, summary, it) }
            ScenarioMode.LIVE -> live()
        }
        inputTokens += response.usage.inputTokens
        outputTokens += response.usage.outputTokens
        cachedInputTokens += response.usage.cachedInputTokens
        return response
    }

    private fun requireDelegate(): LlmClient =
        delegate ?: throw CassetteMismatchException("replay mode has no live LLM to fall back on")

    companion object {
        fun fingerprint(messages: List<LlmMessage>, tools: List<ToolSpec>): String {
            val canonical = buildString {
                tools.sortedBy { it.name }.forEach { append("tool:").append(it.name).append(JSONObject(it.parametersJson).toString()).append('\n') }
                messages.filter { it.role != "system" }.forEach { message ->
                    append(message.role)
                    message.name?.let { append(':').append(it) }
                    message.toolCalls?.let { calls -> append("->").append(calls.joinToString(",") { it.name }) }
                    if (message.role == "user") append('|').append(message.content.orEmpty())
                    append('\n')
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun summarize(messages: List<LlmMessage>, tools: List<ToolSpec>): String {
            val flow = messages.filter { it.role != "system" }.joinToString(" ") { message ->
                when {
                    message.toolCalls != null -> "assistant->${message.toolCalls.joinToString("+") { it.name }}"
                    message.role == "tool" -> "tool:${message.name}"
                    message.role == "user" -> "user(${message.content.orEmpty().take(24)})"
                    else -> message.role
                }
            }
            return "tools=${tools.size} [$flow]"
        }
    }
}
