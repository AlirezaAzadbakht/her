package com.her.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import org.json.JSONObject

/** Separate records keep concurrent answers from overwriting a whole course. */
@Entity(tableName = "learning_records")
data class LearningRecord(
    @PrimaryKey val id: String,
    val pathId: String,
    val kind: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long? = null,
) {
    fun data() = JSONObject(content)
    fun toJson(): String = JSONObject().put("id", id).put("pathId", pathId).put("kind", kind)
        .put("content", content).put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt ?: JSONObject.NULL).toString()

    companion object {
        fun fromJson(j: JSONObject) = LearningRecord(j.getString("id"), j.getString("pathId"),
            j.getString("kind"), j.getString("content"), j.getLong("createdAt"), j.getLong("updatedAt"),
            j.getString("deviceId"), j.getLong("version"), if (j.isNull("deletedAt")) null else j.getLong("deletedAt"))
    }
}

@Dao
interface LearningDao {
    @Query("SELECT * FROM learning_records WHERE id = :id") suspend fun get(id: String): LearningRecord?
    @Query("SELECT * FROM learning_records WHERE deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun all(): List<LearningRecord>
    @Query("SELECT * FROM learning_records WHERE pathId = :pathId AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun forPath(pathId: String): List<LearningRecord>
    @Upsert suspend fun upsert(record: LearningRecord)
}
