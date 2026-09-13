package com.her.data.drive

import com.her.core.newId
import com.her.data.db.SyncCursorEntity
import com.her.data.repository.HerRepository
import com.her.domain.GroceryStatus
import com.her.domain.SyncOp
import com.her.domain.SyncOpType
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class MergeResult(
    val entity: JSONObject,
    val discarded: Boolean = false,
)

object MergeEngine {
    fun applyOps(existing: JSONObject?, ops: List<SyncOp>): JSONObject? {
        var current = existing
        ops.sortedWith(compareBy<SyncOp> { it.createdAt }.thenBy { it.deviceId }.thenBy { it.seq })
            .forEach { op ->
                current = when (op.opType) {
                    SyncOpType.DELETE -> {
                        val obj = current ?: JSONObject().put("id", op.entityId)
                        obj.put("deletedAt", op.createdAt)
                        obj.put("updatedAt", op.createdAt)
                    }
                    SyncOpType.UPSERT -> merge(current, JSONObject(op.payloadJson), op)
                }
            }
        return current
    }

    fun merge(existing: JSONObject?, incoming: JSONObject, op: SyncOp): JSONObject {
        if (existing == null) return incoming
        val merged = JSONObject(existing.toString())
        val incomingKeys = incoming.keys()
        while (incomingKeys.hasNext()) {
            val key = incomingKeys.next()
            if (key == "id") {
                merged.put("id", incoming.get(key))
                continue
            }
            if (key == "status" && incoming.optString("status") == GroceryStatus.PURCHASED.name) {
                merged.put("status", GroceryStatus.PURCHASED.name)
                if (incoming.has("updatedAt")) merged.put("updatedAt", incoming.get("updatedAt"))
                continue
            }
            if (!merged.has(key) || merged.isNull(key)) {
                merged.put(key, incoming.get(key))
                continue
            }
            if (key == "deletedAt") {
                val a = merged.optLong("deletedAt", 0L)
                val b = incoming.optLong("deletedAt", 0L)
                if (b > 0 && (a == 0L || b >= a)) merged.put("deletedAt", incoming.get(key))
                continue
            }
            val existingUpdated = merged.optLong("updatedAt", 0L)
            val incomingUpdated = incoming.optLong("updatedAt", 0L)
            val incomingWins = incomingUpdated > existingUpdated ||
                (incomingUpdated == existingUpdated && op.deviceId > merged.optString("deviceId"))
            if (incomingWins) {
                merged.put(key, incoming.get(key))
            }
        }
        val incomingUpdated = incoming.optLong("updatedAt", 0L)
        if (incomingUpdated >= merged.optLong("updatedAt", 0L)) {
            merged.put("updatedAt", incomingUpdated)
            merged.put("deviceId", incoming.optString("deviceId", op.deviceId))
            merged.put("version", incoming.optLong("version", merged.optLong("version", 1)))
        }
        return merged
    }
}

open class DriveClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    open fun listAppData(token: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder")
                append("&fields=nextPageToken,files(id,name)&pageSize=1000")
                pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            pageToken = http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Drive list failed (${response.code})")
                val root = JSONObject(body)
                val page = root.optJSONArray("files") ?: JSONArray()
                for (i in 0 until page.length()) {
                    val f = page.getJSONObject(i)
                    files += DriveFile(f.getString("id"), f.getString("name"))
                }
                root.optString("nextPageToken").ifBlank { null }
            }
        } while (pageToken != null)
        return files
    }

    open fun download(token: String, fileId: String): String {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Drive download failed (${response.code})")
            return body
        }
    }

    open fun uploadNdjson(token: String, name: String, content: String): String {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put("appDataFolder"))
            .toString()
        val boundary = "her_${newId().replace("-", "")}"
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/x-ndjson\r\n\r\n")
            append(content)
            append("\r\n--$boundary--\r\n")
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Drive upload failed (${response.code}): ${raw.take(200)}")
            return JSONObject(raw).optString("id")
        }
    }

    data class DriveFile(val id: String, val name: String)
}

/** A change-log file name: `{deviceId}/{firstSeq}-{lastSeq}.ndjson`. */
data class ChangeLogName(val deviceId: String, val firstSeq: Long, val lastSeq: Long) {
    companion object {
        private val PATTERN = Regex("""^(.+)/(\d+)-(\d+)\.ndjson$""")

        fun parse(name: String): ChangeLogName? = PATTERN.matchEntire(name)?.let { m ->
            ChangeLogName(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].toLong())
        }
    }
}

data class SyncSummary(val uploaded: Int, val applied: Int, val skipped: Int) {
    override fun toString(): String = "Uploaded $uploaded, applied $applied" + if (skipped > 0) ", skipped $skipped." else "."
}

class SyncEngine(
    private val repo: HerRepository,
    private val drive: DriveClient = DriveClient(),
) {
    suspend fun sync(token: String): SyncSummary {
        val pending = repo.pendingSyncOps()
        if (pending.isNotEmpty()) {
            val first = pending.first().seq
            val last = pending.last().seq
            val payload = pending.joinToString("\n") { opToLine(it) }
            drive.uploadNdjson(token, "${repo.deviceId}/$first-$last.ndjson", payload)
            repo.markUploaded(pending.map { it.id })
        }
        var applied = 0
        var skipped = 0
        // Oldest first per device, so the cursor never jumps past a file that has not been read yet.
        drive.listAppData(token)
            .mapNotNull { file -> ChangeLogName.parse(file.name)?.let { file to it } }
            .filter { (_, log) -> log.deviceId != repo.deviceId }
            .sortedWith(compareBy({ it.second.deviceId }, { it.second.firstSeq }))
            .forEach { (file, log) ->
                val cursor = repo.db.syncDao().cursor(log.deviceId)?.lastSeq ?: 0L
                if (log.lastSeq <= cursor) return@forEach
                val unseen = parseLines(drive.download(token, file.id)).filter { it.seq > cursor }.sortedBy { it.seq }
                repo.applyingRemote {
                    unseen.groupBy { it.entityType to it.entityId }.values.forEach { group ->
                        if (applyRemoteOps(group)) applied += group.size else skipped += group.size
                    }
                }
                val reached = maxOf(log.lastSeq, unseen.maxOfOrNull { it.seq } ?: 0L)
                repo.db.syncDao().upsertCursor(SyncCursorEntity(log.deviceId, reached))
            }
        val summary = SyncSummary(pending.size, applied, skipped)
        repo.logActivity("sync", summary.toString())
        return summary
    }

    private suspend fun applyRemoteOps(ops: List<SyncOp>): Boolean {
        val first = ops.first()
        return try {
            val merged = MergeEngine.applyOps(SyncCodec.current(repo, first.entityType, first.entityId), ops) ?: return false
            SyncCodec.persist(repo, first.entityType, merged)
        } catch (e: Exception) {
            // A delete for a row this device never had, or a malformed payload: nothing to apply.
            repo.logDebug("sync", "Skipped ${first.entityType} ${first.entityId}: ${e.message}")
            false
        }
    }

    private fun opToLine(op: SyncOp): String = JSONObject()
        .put("id", op.id)
        .put("entityType", op.entityType)
        .put("entityId", op.entityId)
        .put("opType", op.opType.name)
        .put("payloadJson", op.payloadJson)
        .put("createdAt", op.createdAt)
        .put("deviceId", op.deviceId)
        .put("seq", op.seq)
        .toString()

    private fun parseLines(text: String): List<SyncOp> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                SyncOp(
                    id = o.getString("id"),
                    entityType = o.getString("entityType"),
                    entityId = o.getString("entityId"),
                    opType = SyncOpType.valueOf(o.getString("opType")),
                    payloadJson = o.getString("payloadJson"),
                    createdAt = o.getLong("createdAt"),
                    deviceId = o.getString("deviceId"),
                    seq = o.getLong("seq"),
                    uploaded = true,
                )
            }.getOrNull()
        }.toList()
}
