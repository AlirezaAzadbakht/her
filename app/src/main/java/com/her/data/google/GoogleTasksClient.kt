package com.her.data.google

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A task as Google Tasks keeps it. Google stores only the date of [due] and drops the time. */
data class RemoteTask(
    val id: String?,
    val title: String,
    val notes: String?,
    val due: LocalDate?,
    val completed: Boolean,
    val deleted: Boolean = false,
    val updatedAt: Long = 0L,
)

/** Google Tasks REST calls for the one "Her" list. Blocking; call it off the main thread. */
open class GoogleTasksClient(
    private val auth: GoogleAuthService? = null,
    private val baseUrl: String = "https://tasks.googleapis.com/tasks/v1",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    open suspend fun token(): String? =
        auth?.authorize(drive = false, calendar = false, tasks = true)?.accessToken?.takeIf { it.isNotBlank() }

    /** The "Her" list, created the first time. */
    open fun herListId(token: String): String {
        val lists = call(token, "GET", "/users/@me/lists?maxResults=100").optJSONArray("items")
        if (lists != null) {
            for (i in 0 until lists.length()) {
                val list = lists.optJSONObject(i) ?: continue
                if (list.optString("title") == LIST_TITLE) return list.getString("id")
            }
        }
        return call(token, "POST", "/users/@me/lists", JSONObject().put("title", LIST_TITLE)).getString("id")
    }

    /** Every task in the list changed after [since], including completed and deleted ones; 0 means all. */
    open fun changedSince(token: String, listId: String, since: Long): List<RemoteTask> {
        val tasks = mutableListOf<RemoteTask>()
        var page: String? = null
        do {
            val query = buildString {
                append("?showCompleted=true&showHidden=true&showDeleted=true&maxResults=100")
                if (since > 0) append("&updatedMin=").append(encode(Instant.ofEpochMilli(since).toString()))
                page?.let { append("&pageToken=").append(encode(it)) }
            }
            val body = call(token, "GET", "/lists/${encode(listId)}/tasks$query")
            body.optJSONArray("items")?.let { items ->
                for (i in 0 until items.length()) items.optJSONObject(i)?.let { parse(it) }?.let { tasks += it }
            }
            page = body.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return tasks
    }

    open fun insert(token: String, listId: String, task: RemoteTask): String =
        call(token, "POST", "/lists/${encode(listId)}/tasks", task.toJson()).getString("id")

    open fun patch(token: String, listId: String, id: String, task: RemoteTask) {
        call(token, "PATCH", "/lists/${encode(listId)}/tasks/${encode(id)}", task.toJson())
    }

    /** Already gone counts as done. */
    open fun delete(token: String, listId: String, id: String) {
        call(token, "DELETE", "/lists/${encode(listId)}/tasks/${encode(id)}", allowMissing = true)
    }

    private fun call(token: String, method: String, path: String, body: JSONObject? = null, allowMissing: Boolean = false): JSONObject {
        val request = Request.Builder()
            .url(baseUrl + path)
            .addHeader("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (allowMissing && (response.code == 404 || response.code == 410)) return JSONObject()
            if (!response.isSuccessful) error("Google Tasks request failed (${response.code})")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun RemoteTask.toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("notes", notes ?: JSONObject.NULL)
        .put("due", due?.let { "${it}T00:00:00.000Z" } ?: JSONObject.NULL)
        .put("status", if (completed) "completed" else "needsAction")

    companion object {
        const val LIST_TITLE = "Her"

        private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        fun parse(obj: JSONObject): RemoteTask? {
            val id = obj.optString("id").ifBlank { return null }
            return RemoteTask(
                id = id,
                title = obj.optString("title"),
                notes = obj.optString("notes").ifBlank { null },
                due = obj.optString("due").takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                completed = obj.optString("status") == "completed",
                deleted = obj.optBoolean("deleted", false),
                updatedAt = obj.optString("updated").let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) },
            )
        }
    }
}
