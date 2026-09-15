package com.her.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
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
import com.her.domain.ReminderRepeat
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import com.her.domain.SyncOpType
import com.her.domain.TaskStatus

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

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val message: String,
    val trigger: ReminderTrigger,
    val fireAt: Long?,
    val repeat: ReminderRepeat,
    val personId: String?,
    val place: String?,
    val onlyIfEntityType: String?,
    val onlyIfEntityId: String?,
    val status: ReminderStatus,
    val firedAt: Long?,
    val sourceMessageId: String?,
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

/** Device-local vector cache for memory retrieval. Not synced; rebuilt when a memory's content or the model changes. */
@Entity(tableName = "memory_embeddings")
data class MemoryEmbeddingEntity(
    @PrimaryKey val memoryId: String,
    val model: String,
    val contentHash: String,
    val dimensions: Int,
    val vector: ByteArray,
    val updatedAt: Long,
)
