package com.her.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import org.json.JSONObject

/** Unanswered observations stay separate from facts and confirmed user patterns. */
@Entity(tableName = "wondering")
data class WonderingRecord(
    @PrimaryKey val id: String,
    val sourceId: String,
    val status: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long? = null,
) {
    fun data() = JSONObject(content)
    fun sourceIds(): Set<String> {
        return setOfNotNull(data().optString("sourceMessageId").takeIf { it.isNotBlank() })
    }

    fun toJson(): String = JSONObject().put("id", id).put("sourceId", sourceId).put("status", status)
        .put("content", content).put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt ?: JSONObject.NULL).toString()

    companion object {
        fun fromJson(j: JSONObject) = WonderingRecord(j.getString("id"), j.getString("sourceId"),
            j.getString("status"), j.getString("content"), j.getLong("createdAt"), j.getLong("updatedAt"),
            j.getString("deviceId"), j.getLong("version"), if (j.isNull("deletedAt")) null else j.getLong("deletedAt"))
    }
}

@Dao
interface WonderingDao {
    @Query("SELECT * FROM wondering WHERE id = :id") suspend fun get(id: String): WonderingRecord?
    @Query("SELECT * FROM wondering WHERE deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun all(): List<WonderingRecord>
    @Upsert suspend fun upsert(record: WonderingRecord)
}
