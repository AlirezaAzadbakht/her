package com.her.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.her.domain.AgentRunStatus
import com.her.domain.AgentRunType
import com.her.domain.CalendarSource
import com.her.domain.CommitmentStatus
import com.her.domain.ConfirmationKind
import com.her.domain.GoalStatus
import com.her.domain.GroceryStatus
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.OpenLoopStatus
import com.her.domain.ProjectStatus
import com.her.domain.QueueStatus
import com.her.domain.SyncOpType
import com.her.domain.TaskStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
    val status: MessageStatus,
    val metadataJson: String?,
)

@Entity(tableName = "chat_messages_fts")
@Fts4(contentEntity = ChatMessageEntity::class)
data class ChatMessageFtsEntity(
    @ColumnInfo(name = "content") val content: String,
)

@Entity(tableName = "short_term_memories")
data class ShortTermMemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val type: String,
    val confidence: Double,
    val importance: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val sourceMessageId: String?,
    val source: MemorySource,
    val metadataJson: String?,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "short_term_memories_fts")
@Fts4(contentEntity = ShortTermMemoryEntity::class)
data class ShortTermMemoryFtsEntity(
    @ColumnInfo(name = "content") val content: String,
)

@Entity(tableName = "long_term_memories")
data class LongTermMemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String,
    val confidence: Double,
    val importance: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConfirmedAt: Long?,
    val source: MemorySource,
    val sourceMessageId: String?,
    val derivedFromJson: String?,
    val validFrom: Long?,
    val validUntil: Long?,
    val status: MemoryStatus,
    val metadataJson: String?,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "long_term_memories_fts")
@Fts4(contentEntity = LongTermMemoryEntity::class)
data class LongTermMemoryFtsEntity(
    @ColumnInfo(name = "content") val content: String,
)

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val userName: String?,
    val assistantName: String?,
    val timezone: String?,
    val preferredLanguage: String?,
    val country: String?,
    val typicalWakeTime: String?,
    val typicalSleepTime: String?,
    val occupationOrStudyContext: String?,
    val firstUseDate: Long?,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
)

@Entity(tableName = "user_understandings")
data class UserUnderstandingEntity(
    @PrimaryKey val id: String,
    val facet: String,
    val content: String,
    val confidence: Double,
    val importance: Double,
    val source: MemorySource,
    val sourceMessageId: String?,
    val status: MemoryStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val relationship: String?,
    val birthday: String?,
    val importantNotes: String?,
    val preferences: String?,
    val lastMentioned: Long?,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val status: ProjectStatus,
    val summary: String?,
    val importance: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val status: GoalStatus,
    val priority: Double,
    val targetDate: Long?,
    val relatedProjectId: String?,
    val progressSummary: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val status: TaskStatus,
    val dueAt: Long?,
    val relatedProjectId: String?,
    val relatedGoalId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "commitments")
data class CommitmentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val status: CommitmentStatus,
    val dueAt: Long?,
    val promisedTo: String?,
    val relatedTaskId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "open_loops")
data class OpenLoopEntity(
    @PrimaryKey val id: String,
    val description: String,
    val status: OpenLoopStatus,
    val importance: Double,
    val confidence: Double,
    val relatedEntityType: String?,
    val relatedEntityId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val schedule: String?,
    val confidence: Double,
    val source: MemorySource,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "groceries")
data class GroceryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val quantity: String?,
    val category: String?,
    val status: GroceryStatus,
    val reason: String?,
    val recurrenceScore: Double,
    val notes: String?,
    val store: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "important_dates")
data class ImportantDateEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dateIso: String,
    val recurrence: String?,
    val relatedPersonId: String?,
    val notes: String?,
    val importance: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "recurring_responsibilities")
data class RecurringResponsibilityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val cadence: String,
    val nextDueAt: Long?,
    val lastCompletedAt: Long?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long?,
    val location: String?,
    val notes: String?,
    val externalId: String?,
    val source: CalendarSource,
    val calendarId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "memory_relationships")
data class MemoryRelationshipEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val sourceId: String,
    val relationshipType: String,
    val targetType: String,
    val targetId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "agent_state")
data class AgentStateEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val content: String,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "agent_queue")
data class AgentQueueEntity(
    @PrimaryKey val id: String,
    val description: String,
    val status: QueueStatus,
    val priority: Double,
    val dueAt: Long?,
    val relatedEntityType: String?,
    val relatedEntityId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "activity_log")
data class ActivityLogEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val category: String,
    val message: String,
    val detailsJson: String?,
)

@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val type: AgentRunType,
    val startedAt: Long,
    val finishedAt: Long?,
    val callsUsed: Int,
    val status: AgentRunStatus,
    val error: String?,
    val notes: String?,
)

@Entity(tableName = "sync_ops")
data class SyncOpEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val opType: SyncOpType,
    val payloadJson: String,
    val createdAt: Long,
    val deviceId: String,
    val seq: Long,
    val uploaded: Boolean,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val remoteDeviceId: String,
    val lastSeq: Long,
)

@Entity(tableName = "api_usage")
data class ApiUsageEntity(
    @PrimaryKey val day: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val hourlyCalls: Int,
    val nightlyCalls: Int,
    val briefingCalls: Int,
    val chatCalls: Int,
    val totalLatencyMs: Long,
    val errors: Int,
)

@Entity(tableName = "debug_events")
data class DebugEventEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val kind: String,
    val payload: String,
)

@Entity(tableName = "pending_confirmations")
data class PendingConfirmationEntity(
    @PrimaryKey val id: String,
    val kind: ConfirmationKind,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long,
)

class HerConverters {
    @TypeConverter fun messageRole(v: MessageRole?): String? = v?.name
    @TypeConverter fun toMessageRole(v: String?): MessageRole? = v?.let { MessageRole.valueOf(it) }
    @TypeConverter fun messageStatus(v: MessageStatus?): String? = v?.name
    @TypeConverter fun toMessageStatus(v: String?): MessageStatus? = v?.let { MessageStatus.valueOf(it) }
    @TypeConverter fun memorySource(v: MemorySource?): String? = v?.name
    @TypeConverter fun toMemorySource(v: String?): MemorySource? = v?.let { MemorySource.valueOf(it) }
    @TypeConverter fun memoryStatus(v: MemoryStatus?): String? = v?.name
    @TypeConverter fun toMemoryStatus(v: String?): MemoryStatus? = v?.let { MemoryStatus.valueOf(it) }
    @TypeConverter fun projectStatus(v: ProjectStatus?): String? = v?.name
    @TypeConverter fun toProjectStatus(v: String?): ProjectStatus? = v?.let { ProjectStatus.valueOf(it) }
    @TypeConverter fun goalStatus(v: GoalStatus?): String? = v?.name
    @TypeConverter fun toGoalStatus(v: String?): GoalStatus? = v?.let { GoalStatus.valueOf(it) }
    @TypeConverter fun taskStatus(v: TaskStatus?): String? = v?.name
    @TypeConverter fun toTaskStatus(v: String?): TaskStatus? = v?.let { TaskStatus.valueOf(it) }
    @TypeConverter fun commitmentStatus(v: CommitmentStatus?): String? = v?.name
    @TypeConverter fun toCommitmentStatus(v: String?): CommitmentStatus? = v?.let { CommitmentStatus.valueOf(it) }
    @TypeConverter fun openLoopStatus(v: OpenLoopStatus?): String? = v?.name
    @TypeConverter fun toOpenLoopStatus(v: String?): OpenLoopStatus? = v?.let { OpenLoopStatus.valueOf(it) }
    @TypeConverter fun groceryStatus(v: GroceryStatus?): String? = v?.name
    @TypeConverter fun toGroceryStatus(v: String?): GroceryStatus? = v?.let { GroceryStatus.valueOf(it) }
    @TypeConverter fun queueStatus(v: QueueStatus?): String? = v?.name
    @TypeConverter fun toQueueStatus(v: String?): QueueStatus? = v?.let { QueueStatus.valueOf(it) }
    @TypeConverter fun calendarSource(v: CalendarSource?): String? = v?.name
    @TypeConverter fun toCalendarSource(v: String?): CalendarSource? = v?.let { CalendarSource.valueOf(it) }
    @TypeConverter fun agentRunType(v: AgentRunType?): String? = v?.name
    @TypeConverter fun toAgentRunType(v: String?): AgentRunType? = v?.let { AgentRunType.valueOf(it) }
    @TypeConverter fun agentRunStatus(v: AgentRunStatus?): String? = v?.name
    @TypeConverter fun toAgentRunStatus(v: String?): AgentRunStatus? = v?.let { AgentRunStatus.valueOf(it) }
    @TypeConverter fun syncOpType(v: SyncOpType?): String? = v?.name
    @TypeConverter fun toSyncOpType(v: String?): SyncOpType? = v?.let { SyncOpType.valueOf(it) }
    @TypeConverter fun confirmationKind(v: ConfirmationKind?): String? = v?.name
    @TypeConverter fun toConfirmationKind(v: String?): ConfirmationKind? = v?.let { ConfirmationKind.valueOf(it) }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE role = :role AND deletedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestByRole(role: MessageRole): Flow<ChatMessageEntity?>

    @Query("SELECT * FROM chat_messages WHERE status = :status AND role = :role AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun pending(status: MessageStatus = MessageStatus.PENDING, role: MessageRole = MessageRole.USER): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE status = :status AND role = :role AND deletedAt IS NULL")
    fun observePendingCount(status: MessageStatus = MessageStatus.PENDING, role: MessageRole = MessageRole.USER): Flow<Int>

    @Query("UPDATE chat_messages SET status = :status, updatedAt = :at, version = version + 1 WHERE id IN (:ids)")
    suspend fun markStatus(ids: List<String>, status: MessageStatus, at: Long)

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ChatMessageEntity?

    @Query(
        """
        SELECT chat_messages.* FROM chat_messages
        JOIN chat_messages_fts ON chat_messages.rowid = chat_messages_fts.rowid
        WHERE chat_messages_fts MATCH :query AND chat_messages.deletedAt IS NULL
        ORDER BY chat_messages.createdAt DESC LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatMessageEntity)
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShort(entity: ShortTermMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLong(entity: LongTermMemoryEntity)

    @Query("SELECT * FROM short_term_memories WHERE id = :id LIMIT 1")
    suspend fun getShort(id: String): ShortTermMemoryEntity?

    @Query("SELECT * FROM long_term_memories WHERE id = :id LIMIT 1")
    suspend fun getLong(id: String): LongTermMemoryEntity?

    @Query("SELECT * FROM short_term_memories WHERE deletedAt IS NULL AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY importance DESC, updatedAt DESC")
    suspend fun activeShort(now: Long): List<ShortTermMemoryEntity>

    @Query("SELECT * FROM long_term_memories WHERE deletedAt IS NULL AND status = :status ORDER BY importance DESC, updatedAt DESC")
    suspend fun longByStatus(status: MemoryStatus): List<LongTermMemoryEntity>

    @Query(
        """
        SELECT short_term_memories.* FROM short_term_memories
        JOIN short_term_memories_fts ON short_term_memories.rowid = short_term_memories_fts.rowid
        WHERE short_term_memories_fts MATCH :query AND short_term_memories.deletedAt IS NULL
        LIMIT :limit
        """,
    )
    suspend fun searchShort(query: String, limit: Int): List<ShortTermMemoryEntity>

    @Query(
        """
        SELECT long_term_memories.* FROM long_term_memories
        JOIN long_term_memories_fts ON long_term_memories.rowid = long_term_memories_fts.rowid
        WHERE long_term_memories_fts MATCH :query AND long_term_memories.deletedAt IS NULL
        LIMIT :limit
        """,
    )
    suspend fun searchLong(query: String, limit: Int): List<LongTermMemoryEntity>

    @Query("UPDATE short_term_memories SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun deleteShort(id: String, at: Long)

    @Query("UPDATE long_term_memories SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun deleteLong(id: String, at: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = :id LIMIT 1")
    suspend fun get(id: String): UserProfileEntity?

    @Query("SELECT * FROM user_profile WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<UserProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserProfileEntity)
}

@Dao
interface UserUnderstandingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserUnderstandingEntity)

    @Query("SELECT * FROM user_understandings WHERE id = :id LIMIT 1")
    suspend fun get(id: String): UserUnderstandingEntity?

    @Query("SELECT * FROM user_understandings WHERE deletedAt IS NULL AND status = :status ORDER BY importance DESC, updatedAt DESC")
    suspend fun byStatus(status: MemoryStatus): List<UserUnderstandingEntity>

    @Query("SELECT * FROM user_understandings WHERE deletedAt IS NULL AND status = :status AND facet = :facet LIMIT 1")
    suspend fun byFacet(facet: String, status: MemoryStatus): UserUnderstandingEntity?
}

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonEntity)

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PersonEntity?

    @Query("SELECT * FROM people WHERE deletedAt IS NULL ORDER BY lastMentioned DESC, name ASC")
    suspend fun allActive(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE deletedAt IS NULL AND (name LIKE :q OR relationship LIKE :q OR importantNotes LIKE :q)")
    suspend fun search(q: String): List<PersonEntity>

    @Query("UPDATE people SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE deletedAt IS NULL ORDER BY importance DESC, updatedAt DESC")
    suspend fun allActive(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE deletedAt IS NULL AND (name LIKE :q OR description LIKE :q OR summary LIKE :q)")
    suspend fun search(q: String): List<ProjectEntity>

    @Query("UPDATE projects SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id LIMIT 1")
    suspend fun get(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE deletedAt IS NULL ORDER BY priority DESC, updatedAt DESC")
    suspend fun allActive(): List<GoalEntity>

    @Query("UPDATE goals SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL ORDER BY dueAt IS NULL, dueAt ASC")
    suspend fun allActive(): List<TaskEntity>

    @Query("UPDATE tasks SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface CommitmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommitmentEntity)

    @Query("SELECT * FROM commitments WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CommitmentEntity?

    @Query("SELECT * FROM commitments WHERE deletedAt IS NULL ORDER BY dueAt IS NULL, dueAt ASC")
    suspend fun allActive(): List<CommitmentEntity>

    @Query("UPDATE commitments SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface OpenLoopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OpenLoopEntity)

    @Query("SELECT * FROM open_loops WHERE id = :id LIMIT 1")
    suspend fun get(id: String): OpenLoopEntity?

    @Query("SELECT * FROM open_loops WHERE deletedAt IS NULL ORDER BY importance DESC")
    suspend fun allActive(): List<OpenLoopEntity>

    @Query("UPDATE open_loops SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RoutineEntity)

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun get(id: String): RoutineEntity?

    @Query("SELECT * FROM routines WHERE deletedAt IS NULL ORDER BY confidence DESC")
    suspend fun allActive(): List<RoutineEntity>

    @Query("UPDATE routines SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface GroceryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroceryEntity)

    @Query("SELECT * FROM groceries WHERE id = :id LIMIT 1")
    suspend fun get(id: String): GroceryEntity?

    @Query("SELECT * FROM groceries WHERE deletedAt IS NULL ORDER BY status ASC, name ASC")
    suspend fun allActive(): List<GroceryEntity>

    @Query("UPDATE groceries SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface ImportantDateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportantDateEntity)

    @Query("SELECT * FROM important_dates WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ImportantDateEntity?

    @Query("SELECT * FROM important_dates WHERE deletedAt IS NULL ORDER BY dateIso ASC")
    suspend fun allActive(): List<ImportantDateEntity>

    @Query("UPDATE important_dates SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface ResponsibilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecurringResponsibilityEntity)

    @Query("SELECT * FROM recurring_responsibilities WHERE id = :id LIMIT 1")
    suspend fun get(id: String): RecurringResponsibilityEntity?

    @Query("SELECT * FROM recurring_responsibilities WHERE deletedAt IS NULL ORDER BY nextDueAt IS NULL, nextDueAt ASC")
    suspend fun allActive(): List<RecurringResponsibilityEntity>

    @Query("UPDATE recurring_responsibilities SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface CalendarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_events WHERE externalId = :externalId ORDER BY CASE WHEN deletedAt IS NULL THEN 0 ELSE 1 END ASC LIMIT 1")
    suspend fun getByExternalId(externalId: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_events WHERE deletedAt IS NULL AND startAt < :to AND COALESCE(endAt, startAt + 1) > :from ORDER BY startAt ASC")
    suspend fun inRange(from: Long, to: Long): List<CalendarEventEntity>

    @Query("UPDATE calendar_events SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface RelationshipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryRelationshipEntity)

    @Query("SELECT * FROM memory_relationships WHERE deletedAt IS NULL")
    suspend fun allActive(): List<MemoryRelationshipEntity>

    @Query("SELECT * FROM memory_relationships WHERE deletedAt IS NULL AND ((sourceType = :type AND sourceId = :id) OR (targetType = :type AND targetId = :id))")
    suspend fun forEntity(type: String, id: String): List<MemoryRelationshipEntity>

    @Query("UPDATE memory_relationships SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: AgentStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueue(entity: AgentQueueEntity)

    @Query("SELECT * FROM agent_state WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun stateEntries(): List<AgentStateEntity>

    @Query("SELECT * FROM agent_queue WHERE deletedAt IS NULL ORDER BY priority DESC, createdAt ASC")
    suspend fun queueItems(): List<AgentQueueEntity>

    @Query("SELECT * FROM agent_state WHERE id = :id LIMIT 1")
    suspend fun getState(id: String): AgentStateEntity?

    @Query("SELECT * FROM agent_queue WHERE id = :id LIMIT 1")
    suspend fun getQueue(id: String): AgentQueueEntity?

    @Query("SELECT * FROM agent_state WHERE deletedAt IS NULL")
    fun observeState(): Flow<List<AgentStateEntity>>

    @Query("SELECT * FROM agent_queue WHERE deletedAt IS NULL")
    fun observeQueue(): Flow<List<AgentQueueEntity>>

    @Query("UPDATE agent_state SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun deleteState(id: String, at: Long)

    @Query("UPDATE agent_queue SET deletedAt = :at, updatedAt = :at, version = version + 1 WHERE id = :id")
    suspend fun deleteQueue(id: String, at: Long)
}

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(entity: ActivityLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebug(entity: DebugEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(entity: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(entity: ApiUsageEntity)

    @Query("SELECT * FROM activity_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeActivity(limit: Int): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM debug_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeDebug(limit: Int): Flow<List<DebugEventEntity>>

    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRuns(limit: Int): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM api_usage WHERE day = :day LIMIT 1")
    suspend fun usage(day: String): ApiUsageEntity?

    @Query("SELECT * FROM api_usage WHERE day = :day LIMIT 1")
    fun observeUsage(day: String): Flow<ApiUsageEntity?>
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOp(entity: SyncOpEntity)

    @Query("SELECT * FROM sync_ops WHERE uploaded = 0 ORDER BY seq ASC")
    suspend fun pending(): List<SyncOpEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM sync_ops WHERE deviceId = :deviceId")
    suspend fun lastSeq(deviceId: String): Long

    @Query("UPDATE sync_ops SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("SELECT * FROM sync_cursors WHERE remoteDeviceId = :id LIMIT 1")
    suspend fun cursor(id: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(entity: SyncCursorEntity)
}

@Dao
interface ConfirmationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingConfirmationEntity)

    @Query("SELECT * FROM pending_confirmations WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PendingConfirmationEntity?

    @Query("DELETE FROM pending_confirmations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        ChatMessageEntity::class,
        ChatMessageFtsEntity::class,
        ShortTermMemoryEntity::class,
        ShortTermMemoryFtsEntity::class,
        LongTermMemoryEntity::class,
        LongTermMemoryFtsEntity::class,
        UserProfileEntity::class,
        UserUnderstandingEntity::class,
        PersonEntity::class,
        ProjectEntity::class,
        GoalEntity::class,
        TaskEntity::class,
        CommitmentEntity::class,
        OpenLoopEntity::class,
        RoutineEntity::class,
        GroceryEntity::class,
        ImportantDateEntity::class,
        RecurringResponsibilityEntity::class,
        CalendarEventEntity::class,
        MemoryRelationshipEntity::class,
        AgentStateEntity::class,
        AgentQueueEntity::class,
        ActivityLogEntity::class,
        AgentRunEntity::class,
        SyncOpEntity::class,
        SyncCursorEntity::class,
        ApiUsageEntity::class,
        DebugEventEntity::class,
        PendingConfirmationEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(HerConverters::class)
abstract class HerDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun profileDao(): ProfileDao
    abstract fun userUnderstandingDao(): UserUnderstandingDao
    abstract fun personDao(): PersonDao
    abstract fun projectDao(): ProjectDao
    abstract fun goalDao(): GoalDao
    abstract fun taskDao(): TaskDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun openLoopDao(): OpenLoopDao
    abstract fun routineDao(): RoutineDao
    abstract fun groceryDao(): GroceryDao
    abstract fun importantDateDao(): ImportantDateDao
    abstract fun responsibilityDao(): ResponsibilityDao
    abstract fun calendarDao(): CalendarDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun agentDao(): AgentDao
    abstract fun logDao(): LogDao
    abstract fun syncDao(): SyncDao
    abstract fun confirmationDao(): ConfirmationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_understandings` (
                        `id` TEXT NOT NULL,
                        `facet` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `importance` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `sourceMessageId` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
