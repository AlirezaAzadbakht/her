package com.her.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import org.json.JSONObject

/** Separate developments preserve history without rewriting a whole life thread. */
@Entity(tableName = "companion_records")
data class CompanionRecord(
    @PrimaryKey val id: String,
    val parentId: String,
    val kind: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long? = null,
) {
    fun data() = JSONObject(content)
    fun sourceIds(): Set<String> {
        val data = data()
        val quotes = data.optJSONObject("sourceQuotes")
        return buildSet {
            data.optString("sourceMessageId").takeIf { it.isNotBlank() }?.let { add(it) }
            quotes?.keys()?.forEach { key -> quotes.optJSONObject(key)?.optString("sourceMessageId")?.let { add(it) } }
        }
    }
    fun toJson(): String = JSONObject().put("id", id).put("parentId", parentId).put("kind", kind)
        .put("content", content).put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt ?: JSONObject.NULL).toString()

    companion object {
        fun fromJson(j: JSONObject) = CompanionRecord(j.getString("id"), j.getString("parentId"),
            j.getString("kind"), j.getString("content"), j.getLong("createdAt"), j.getLong("updatedAt"),
            j.getString("deviceId"), j.getLong("version"), if (j.isNull("deletedAt")) null else j.getLong("deletedAt"))
    }
}

@Dao
interface CompanionDao {
    @Query("SELECT * FROM companion_records WHERE id = :id") suspend fun get(id: String): CompanionRecord?
    @Query("SELECT * FROM companion_records WHERE deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun all(): List<CompanionRecord>
    @Query("SELECT * FROM companion_records WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun forParent(parentId: String): List<CompanionRecord>
    @Upsert suspend fun upsert(record: CompanionRecord)
}
