package com.her.data.drive

import com.her.core.newId
import com.her.data.db.SyncCursorEntity
import com.her.data.db.SyncOpEntity
import com.her.data.repository.HerRepository
import com.her.data.repository.toJson
import com.her.domain.GroceryStatus
import com.her.domain.SyncOp
import com.her.domain.SyncOpType
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

class DriveClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    fun listAppData(token: String): List<DriveFile> {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id,name)&pageSize=100")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Drive list failed (${response.code})")
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            return buildList {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    add(DriveFile(f.getString("id"), f.getString("name")))
                }
            }
        }
    }

    fun download(token: String, fileId: String): String {
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

    fun uploadNdjson(token: String, name: String, content: String): String {
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

class SyncEngine(
    private val repo: HerRepository,
    private val drive: DriveClient = DriveClient(),
) {
    suspend fun sync(token: String): String {
        val pending = repo.pendingSyncOps()
        if (pending.isNotEmpty()) {
            val deviceId = repo.deviceId
            val first = pending.first().seq
            val last = pending.last().seq
            val name = "$deviceId/$first-$last.ndjson"
            val payload = pending.joinToString("\n") { opToLine(it) }
            drive.uploadNdjson(token, name, payload)
            repo.markUploaded(pending.map { it.id })
        }
        val files = drive.listAppData(token)
        var applied = 0
        files.filter { it.name.contains("/") && it.name.endsWith(".ndjson") }.forEach { file ->
            val remoteDevice = file.name.substringBefore("/")
            if (remoteDevice == repo.deviceId) return@forEach
            val text = drive.download(token, file.id)
            val ops = parseLines(text)
            if (ops.isEmpty()) return@forEach
            val cursor = repo.db.syncDao().cursor(remoteDevice)?.lastSeq ?: 0L
            val unseen = ops.filter { it.seq > cursor }
            unseen.groupBy { it.entityType to it.entityId }.forEach { (_, group) ->
                applyRemoteOps(group)
                applied += group.size
            }
            unseen.maxOfOrNull { it.seq }?.let {
                repo.db.syncDao().upsertCursor(SyncCursorEntity(remoteDevice, it))
            }
        }
        repo.logActivity("sync", "Uploaded ${pending.size} ops, applied $applied remote ops.")
        return "Uploaded ${pending.size}, applied $applied."
    }

    private suspend fun applyRemoteOps(ops: List<SyncOp>) {
        val first = ops.first()
        val existingJson = existingPayload(first.entityType, first.entityId)
        val merged = MergeEngine.applyOps(existingJson, ops) ?: return
        persistMerged(first.entityType, merged)
    }

    private suspend fun existingPayload(type: String, id: String): JSONObject? {
        val raw = when (type) {
            "groceries" -> repo.getGrocery(id)?.toJson()
            "chat_messages" -> repo.getMessage(id)?.toJson()
            else -> null
        }
        return raw?.let { JSONObject(it) }
    }

    private suspend fun persistMerged(type: String, json: JSONObject) {
        when (type) {
            "groceries" -> {
                val item = repo.getGrocery(json.getString("id"))
                if (item != null) {
                    repo.saveGrocery(
                        item.copy(
                            name = json.optString("name", item.name),
                            status = runCatching { GroceryStatus.valueOf(json.optString("status", item.status.name)) }.getOrDefault(item.status),
                            quantity = json.optString("quantity").takeIf { it.isNotBlank() } ?: item.quantity,
                            updatedAt = json.optLong("updatedAt", item.updatedAt),
                            version = json.optLong("version", item.version),
                            deletedAt = json.optLong("deletedAt").takeIf { it > 0 },
                            deviceId = json.optString("deviceId", item.deviceId),
                        ),
                    )
                }
            }
            else -> repo.logDebug("sync", "Merged $type ${json.optString("id")}")
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
