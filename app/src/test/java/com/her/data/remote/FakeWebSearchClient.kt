package com.her.data.remote

import com.her.data.secure.AppSettings
import org.json.JSONObject

class FakeWebSearchClient : WebSearchClient() {
    data class Fixture(
        val queryContainsAny: List<String>,
        val title: String,
        val snippets: String,
    )

    private val fixtures = mutableListOf<Fixture>()

    fun seed(results: List<Fixture>) {
        fixtures.clear()
        fixtures.addAll(results)
    }

    override suspend fun search(
        query: String,
        settings: AppSettings,
        country: String?,
        timezone: String?,
    ): String {
        if (!settings.webSearchEnabled) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Web search is disabled. Enable it in Settings if you want me to look things up.")
                .toString()
        }
        val match = fixtures.firstOrNull { fixture ->
            fixture.queryContainsAny.any { needle -> query.contains(needle, ignoreCase = true) }
        }
        if (match == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "No search results.")
                .toString()
        }
        return JSONObject()
            .put("ok", true)
            .put("title", match.title)
            .put("snippets", match.snippets)
            .toString()
    }

    /** A seeded hit doubles as a page: the url is matched against the same needles. */
    override suspend fun fetch(url: String): FetchedPage {
        val match = fixtures.firstOrNull { fixture -> fixture.queryContainsAny.any { url.contains(it, ignoreCase = true) } }
            ?: throw java.io.IOException("No such page.")
        return FetchedPage(url, match.title, match.snippets)
    }
}
