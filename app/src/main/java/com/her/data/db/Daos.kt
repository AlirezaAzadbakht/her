package com.her.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM long_term_memories WHERE deletedAt IS NULL")
    suspend fun allLong(): List<LongTermMemoryEntity>

    @Query(
        """
        SELECT short_term_memories.* FROM short_term_memories
        JOIN short_term_memories_fts ON short_term_memories.rowid = short_term_memories_fts.rowid
        WHERE short_term_memories_fts MATCH :query AND short_term_memories.deletedAt IS NULL
          AND (short_term_memories.expiresAt IS NULL OR short_term_memories.expiresAt > :now)
        LIMIT :limit
        """,
    )
    suspend fun searchShort(query: String, now: Long, limit: Int): List<ShortTermMemoryEntity>

    @Query(
        """
        SELECT long_term_memories.* FROM long_term_memories
        JOIN long_term_memories_fts ON long_term_memories.rowid = long_term_memories_fts.rowid
        WHERE long_term_memories_fts MATCH :query AND long_term_memories.deletedAt IS NULL
          AND long_term_memories.status = 'ACTIVE'
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
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE deletedAt IS NULL ORDER BY fireAt IS NULL, fireAt ASC, createdAt ASC")
    suspend fun allActive(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE deletedAt IS NULL AND status = 'SCHEDULED' AND `trigger` = 'TIME' AND fireAt <= :now AND deviceId = :deviceId ORDER BY fireAt ASC")
    suspend fun dueTime(now: Long, deviceId: String): List<ReminderEntity>
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

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM memory_embeddings WHERE model = :model AND memoryId IN (:ids)")
    suspend fun forIds(ids: List<String>, model: String): List<MemoryEmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MemoryEmbeddingEntity>)

    @Query("SELECT COUNT(*) FROM memory_embeddings")
    suspend fun count(): Int
}
