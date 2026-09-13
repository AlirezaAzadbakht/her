package com.her.data.remote

import com.her.data.secure.AppSettings
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

open class WebSearchClient(
    private val llm: LlmClient = LlmClient(),
    private val llmSettings: () -> LlmSettings = { LlmSettings("", "", "") },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    open suspend fun search(
        query: String,
        settings: AppSettings,
        country: String? = null,
        timezone: String? = null,
    ): String {
        if (!settings.webSearchEnabled) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Web search is disabled. Enable it in Settings if you want me to look things up.")
                .toString()
        }
        val custom = settings.webSearchEndpoint.trim()
        return try {
            if (custom.isNotBlank()) {
                searchCustom(query, custom, settings.webSearchApiKey)
            } else {
                searchWithProvider(query, country, timezone)
            }
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "I couldn't check that online right now.")
                .put("detail", e.message)
                .toString()
        }
    }

    private suspend fun searchWithProvider(query: String, country: String?, timezone: String?): String {
        val configured = llmSettings()
        if (!configured.isConfigured) {
            return JSONObject()
                .put("ok", false)
                .put("error", "LLM is not configured. Add a base URL, API key, and model in Settings.")
                .toString()
        }
        val response = llm.complete(
            settings = configured,
            messages = listOf(
                LlmMessage(role = "system", content = SEARCH_PROMPT),
                LlmMessage(role = "user", content = query.trim()),
            ),
            webSearch = WebSearchRequest(country = country, timezone = timezone),
        )
        val snippets = response.message.content?.trim().orEmpty()
        val citations = citationsFrom(response.rawJson)
        return JSONObject()
            .put("ok", snippets.isNotBlank())
            .put("snippets", snippets)
            .put("citations", citations)
            .put("note", if (snippets.isBlank()) "No useful results. Personal questions should use memory first." else "")
            .toString()
    }

    private fun searchCustom(query: String, endpoint: String, apiKey: String): String {
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("q", query)?.build()
            ?: throw IllegalArgumentException("Invalid search endpoint")
        val request = Request.Builder().url(url).apply {
            if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
        }.get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return JSONObject().put("ok", false).put("error", "Search failed (${response.code})").toString()
            }
            return JSONObject().put("ok", true).put("results", body.take(4000)).toString()
        }
    }

    companion object {
        internal const val SEARCH_PROMPT =
            "Extract current public facts for this query. Do not invent personal details about the user. Prefer short factual snippets. If you cannot find anything, say so."

        internal fun citationsFrom(raw: String): JSONArray {
            val citations = JSONArray()
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return citations
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return citations
            val annotations = message.optJSONArray("annotations") ?: return citations
            for (i in 0 until annotations.length()) {
                val item = annotations.optJSONObject(i) ?: continue
                val citation = item.optJSONObject("url_citation") ?: item
                val url = citation.optString("url").ifBlank { item.optString("url") }
                val title = citation.optString("title").ifBlank { item.optString("title") }
                if (url.isBlank()) continue
                citations.put(JSONObject().put("url", url).put("title", title))
            }
            return citations
        }
    }
}
