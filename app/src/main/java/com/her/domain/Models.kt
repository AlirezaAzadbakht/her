package com.her.domain

enum class MessageRole { USER, ASSISTANT, SYSTEM }
enum class MessageStatus { PENDING, SENT, FAILED }
enum class MemorySource { USER_EXPLICIT, AGENT_INFERENCE, SYSTEM, SHARED }
enum class MemoryStatus { ACTIVE, HISTORICAL, ARCHIVED }
enum class ProjectStatus { ACTIVE, PAUSED, DONE, DROPPED }
enum class GoalStatus { ACTIVE, PAUSED, DONE, DROPPED }
enum class TaskStatus { OPEN, DONE, DROPPED }
enum class CommitmentStatus { OPEN, DONE, MISSED, DROPPED }
enum class OpenLoopStatus { OPEN, CLOSED, DROPPED }
enum class GroceryStatus { ACTIVE, PURCHASED, DROPPED }
enum class QueueStatus { OPEN, DONE, DROPPED }
enum class CalendarSource { INTERNAL, SYSTEM, GOOGLE }
enum class AgentRunType { CHAT, HOURLY, NIGHTLY, BRIEFING, CATCH_UP }
enum class AgentRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }
enum class SyncOpType { UPSERT, DELETE }
enum class ConfirmationKind { CALENDAR_DELETE, BULK_FORGET, EXTERNAL_DESTRUCTIVE }

data class ChatMessage(
    val id: String,
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

data class ShortTermMemory(
    val id: String,
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

data class LongTermMemory(
    val id: String,
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

data class UserProfile(
    val id: String = PROFILE_ID,
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
) {
    companion object {
        const val PROFILE_ID = "user-profile"
    }
}

data class Person(
    val id: String,
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

data class Project(
    val id: String,
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

data class Goal(
    val id: String,
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

data class TaskItem(
    val id: String,
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

data class Commitment(
    val id: String,
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

data class OpenLoop(
    val id: String,
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

data class Routine(
    val id: String,
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

data class GroceryItem(
    val id: String,
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

data class ImportantDate(
    val id: String,
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

data class RecurringResponsibility(
    val id: String,
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

data class CalendarEvent(
    val id: String,
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

data class MemoryRelationship(
    val id: String,
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

data class AgentStateEntry(
    val id: String,
    val kind: String,
    val content: String,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val version: Long,
    val deletedAt: Long?,
)

data class AgentQueueItem(
    val id: String,
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

data class ActivityLogEntry(
    val id: String,
    val createdAt: Long,
    val category: String,
    val message: String,
    val detailsJson: String?,
)

data class AgentRun(
    val id: String,
    val type: AgentRunType,
    val startedAt: Long,
    val finishedAt: Long?,
    val callsUsed: Int,
    val status: AgentRunStatus,
    val error: String?,
    val notes: String?,
)

data class SyncOp(
    val id: String,
    val entityType: String,
    val entityId: String,
    val opType: SyncOpType,
    val payloadJson: String,
    val createdAt: Long,
    val deviceId: String,
    val seq: Long,
    val uploaded: Boolean,
)

data class ApiUsageDay(
    val day: String,
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

data class DebugEvent(
    val id: String,
    val createdAt: Long,
    val kind: String,
    val payload: String,
)

data class PendingConfirmation(
    val id: String,
    val kind: ConfirmationKind,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long,
)

data class MemoryHit(
    val id: String,
    val memoryType: String,
    val content: String,
    val importance: Double,
    val confidence: Double,
    val updatedAt: Long,
    val score: Double,
    val source: String?,
    val sourceMessageId: String?,
    val derivedFromJson: String?,
)

data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long,
)

data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LlmToolCall>? = null,
    val name: String? = null,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class LlmResponse(
    val message: LlmMessage,
    val usage: LlmUsage,
    val rawJson: String,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)

data class ToolResult(
    val name: String,
    val ok: Boolean,
    val payloadJson: String,
)
