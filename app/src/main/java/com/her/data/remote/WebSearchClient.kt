package com.her.data.remote

import com.her.data.secure.AppSettings
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WebSearchClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    fun search(query: String, settings: AppSettings): String {
        if (!settings.webSearchEnabled) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Web search is disabled. Enable it in Settings if you want me to look things up.")
                .toString()
        }
        val custom = settings.webSearchEndpoint.trim()
        return try {
            if (custom.isNotBlank()) searchCustom(query, custom, settings.webSearchApiKey) else searchDuckDuckGo(query)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "I couldn't check that online right now.").put("detail", e.message).toString()
        }
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

    private fun searchDuckDuckGo(query: String): String {
        val url = "https://api.duckduckgo.com/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return JSONObject().put("ok", false).put("error", "I couldn't check that online right now.").toString()
            }
            val json = JSONObject(body)
            val abstract = json.optString("AbstractText")
            val heading = json.optString("Heading")
            val related = json.optJSONArray("RelatedTopics")
            val snippets = buildList {
                if (abstract.isNotBlank()) add(abstract)
                if (related != null) {
                    for (i in 0 until minOf(4, related.length())) {
                        val item = related.optJSONObject(i) ?: continue
                        item.optString("Text").takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            return JSONObject()
                .put("ok", snippets.isNotEmpty())
                .put("title", heading)
                .put("snippets", snippets.joinToString("\n"))
                .put("note", if (snippets.isEmpty()) "No instant answer. Personal questions should use memory first." else "")
                .toString()
        }
    }
}
