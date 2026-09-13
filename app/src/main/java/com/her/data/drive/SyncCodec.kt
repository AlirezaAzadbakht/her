package com.her.data.drive

import com.her.data.repository.HerRepository
import com.her.data.repository.toJson
import com.her.domain.AgentQueueItem
import com.her.domain.AgentStateEntry
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import com.her.domain.ChatMessage
import com.her.domain.Commitment
import com.her.domain.CommitmentStatus
import com.her.domain.Goal
import com.her.domain.GoalStatus
import com.her.domain.GroceryItem
import com.her.domain.GroceryStatus
import com.her.domain.ImportantDate
import com.her.domain.LongTermMemory
import com.her.domain.MemoryRelationship
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.OpenLoop
import com.her.domain.OpenLoopStatus
import com.her.domain.Person
import com.her.domain.Project
import com.her.domain.ProjectStatus
import com.her.domain.QueueStatus
import com.her.domain.RecurringResponsibility
import com.her.domain.Routine
import com.her.domain.ShortTermMemory
import com.her.domain.TaskItem
import com.her.domain.TaskStatus
import com.her.domain.UserProfile
import com.her.domain.UserUnderstanding
import org.json.JSONObject

/**
 * Reads the current local row for a sync op and writes a merged row back.
 * The JSON shape is the one `toJson()` in the repository produces for each record.
 */
internal object SyncCodec {
    suspend fun current(repo: HerRepository, type: String, id: String): JSONObject? {
        val raw = when (type) {
            "chat_messages" -> repo.getMessage(id)?.toJson()
            "short_term_memories" -> repo.getShort(id)?.toJson()
            "long_term_memories" -> repo.getLong(id)?.toJson()
            "user_profile" -> if (id == UserProfile.PROFILE_ID) repo.getProfile().toJson() else null
            "user_understandings" -> repo.getUserUnderstanding(id)?.toJson()
            "people" -> repo.getPerson(id)?.toJson()
            "projects" -> repo.getProject(id)?.toJson()
            "goals" -> repo.getGoal(id)?.toJson()
            "tasks" -> repo.getTask(id)?.toJson()
            "commitments" -> repo.getCommitment(id)?.toJson()
            "open_loops" -> repo.getOpenLoop(id)?.toJson()
            "routines" -> repo.getRoutine(id)?.toJson()
            "groceries" -> repo.getGrocery(id)?.toJson()
            "important_dates" -> repo.getImportantDate(id)?.toJson()
            "recurring_responsibilities" -> repo.getResponsibility(id)?.toJson()
            "calendar_events" -> repo.getCalendarEvent(id)?.toJson()
            "memory_relationships" -> repo.relationships().firstOrNull { it.id == id }?.toJson()
            "agent_state" -> repo.getAgentState(id)?.toJson()
            "agent_queue" -> repo.getAgentQueue(id)?.toJson()
            else -> null
        }
        return raw?.let(::JSONObject)
    }

    /** False when the type is not synced or the row belongs to this device only (calendar mirrors). */
    suspend fun persist(repo: HerRepository, type: String, json: JSONObject): Boolean {
        when (type) {
            "chat_messages" -> repo.saveMessage(chatMessage(json))
            "short_term_memories" -> repo.upsertShort(shortTerm(json))
            "long_term_memories" -> repo.upsertLong(longTerm(json))
            "user_profile" -> repo.saveProfile(profile(json))
            "user_understandings" -> repo.saveUserUnderstanding(understanding(json))
            "people" -> repo.savePerson(person(json))
            "projects" -> repo.saveProject(project(json))
            "goals" -> repo.saveGoal(goal(json))
            "tasks" -> repo.saveTask(task(json))
            "commitments" -> repo.saveCommitment(commitment(json))
            "open_loops" -> repo.saveOpenLoop(openLoop(json))
            "routines" -> repo.saveRoutine(routine(json))
            "groceries" -> repo.saveGrocery(grocery(json))
            "important_dates" -> repo.saveImportantDate(importantDate(json))
            "recurring_responsibilities" -> repo.saveResponsibility(responsibility(json))
            "calendar_events" -> {
                val event = calendarEvent(json)
                // Device and Google calendar rows are mirrors of this phone's own sources; each device mirrors its own.
                if (event.source != CalendarSource.INTERNAL) return false
                repo.saveCalendarEvent(event)
            }
            "memory_relationships" -> repo.saveRelationship(relationship(json))
            "agent_state" -> repo.saveAgentState(agentState(json))
            "agent_queue" -> repo.saveAgentQueue(agentQueue(json))
            else -> return false
        }
        return true
    }

    private class Meta(json: JSONObject) {
        val id: String = json.getString("id")
        val updatedAt: Long = json.optLong("updatedAt")
        val createdAt: Long = json.optLong("createdAt", updatedAt)
        val deviceId: String = json.optString("deviceId")
        val version: Long = json.optLong("version", 1)
        val deletedAt: Long? = json.longOrNull("deletedAt")
    }

    private fun chatMessage(json: JSONObject): ChatMessage {
        val m = Meta(json)
        // Another device's unsent message must never be picked up by this device's outbox.
        val status = json.enumOr("status", MessageStatus.SENT).let { if (it == MessageStatus.PENDING) MessageStatus.SENT else it }
        return ChatMessage(
            id = m.id,
            role = enumValueOf<MessageRole>(json.getString("role")),
            content = json.getString("content"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
            status = status,
            metadataJson = json.stringOrNull("metadataJson"),
        )
    }

    private fun shortTerm(json: JSONObject): ShortTermMemory {
        val m = Meta(json)
        return ShortTermMemory(
            id = m.id,
            content = json.getString("content"),
            type = json.optString("type", "context"),
            confidence = json.optDouble("confidence", 0.6),
            importance = json.optDouble("importance", 0.4),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            expiresAt = json.longOrNull("expiresAt"),
            sourceMessageId = json.stringOrNull("sourceMessageId"),
            source = json.enumOr("source", MemorySource.AGENT_INFERENCE),
            metadataJson = json.stringOrNull("metadataJson"),
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun longTerm(json: JSONObject): LongTermMemory {
        val m = Meta(json)
        return LongTermMemory(
            id = m.id,
            content = json.getString("content"),
            category = json.optString("category", "general"),
            confidence = json.optDouble("confidence", 0.7),
            importance = json.optDouble("importance", 0.5),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            lastConfirmedAt = json.longOrNull("lastConfirmedAt"),
            source = json.enumOr("source", MemorySource.AGENT_INFERENCE),
            sourceMessageId = json.stringOrNull("sourceMessageId"),
            derivedFromJson = json.stringOrNull("derivedFromJson"),
            validFrom = json.longOrNull("validFrom"),
            validUntil = json.longOrNull("validUntil"),
            status = json.enumOr("status", MemoryStatus.ACTIVE),
            metadataJson = json.stringOrNull("metadataJson"),
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun profile(json: JSONObject) = UserProfile(
        id = UserProfile.PROFILE_ID,
        userName = json.stringOrNull("userName"),
        assistantName = json.stringOrNull("assistantName"),
        timezone = json.stringOrNull("timezone"),
        preferredLanguage = json.stringOrNull("preferredLanguage"),
        country = json.stringOrNull("country"),
        typicalWakeTime = json.stringOrNull("typicalWakeTime"),
        typicalSleepTime = json.stringOrNull("typicalSleepTime"),
        occupationOrStudyContext = json.stringOrNull("occupationOrStudyContext"),
        firstUseDate = json.longOrNull("firstUseDate"),
        updatedAt = json.optLong("updatedAt"),
        deviceId = json.optString("deviceId"),
        version = json.optLong("version", 1),
    )

    private fun understanding(json: JSONObject): UserUnderstanding {
        val m = Meta(json)
        return UserUnderstanding(
            id = m.id,
            facet = UserUnderstanding.normalizeFacet(json.stringOrNull("facet")),
            content = json.getString("content"),
            confidence = json.optDouble("confidence", 0.7),
            importance = json.optDouble("importance", 0.6),
            source = json.enumOr("source", MemorySource.AGENT_INFERENCE),
            sourceMessageId = json.stringOrNull("sourceMessageId"),
            status = json.enumOr("status", MemoryStatus.ACTIVE),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun person(json: JSONObject): Person {
        val m = Meta(json)
        return Person(
            id = m.id,
            name = json.getString("name"),
            relationship = json.stringOrNull("relationship"),
            birthday = json.stringOrNull("birthday"),
            importantNotes = json.stringOrNull("importantNotes"),
            preferences = json.stringOrNull("preferences"),
            lastMentioned = json.longOrNull("lastMentioned"),
            confidence = json.optDouble("confidence", 0.8),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun project(json: JSONObject): Project {
        val m = Meta(json)
        return Project(
            id = m.id,
            name = json.getString("name"),
            description = json.stringOrNull("description"),
            status = json.enumOr("status", ProjectStatus.ACTIVE),
            summary = json.stringOrNull("summary"),
            importance = json.optDouble("importance", 0.5),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun goal(json: JSONObject): Goal {
        val m = Meta(json)
        return Goal(
            id = m.id,
            title = json.getString("title"),
            description = json.stringOrNull("description"),
            status = json.enumOr("status", GoalStatus.ACTIVE),
            priority = json.optDouble("priority", 0.6),
            targetDate = json.longOrNull("targetDate"),
            relatedProjectId = json.stringOrNull("relatedProjectId"),
            progressSummary = json.stringOrNull("progressSummary"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun task(json: JSONObject): TaskItem {
        val m = Meta(json)
        return TaskItem(
            id = m.id,
            title = json.getString("title"),
            description = json.stringOrNull("description"),
            status = json.enumOr("status", TaskStatus.OPEN),
            dueAt = json.longOrNull("dueAt"),
            relatedProjectId = json.stringOrNull("relatedProjectId"),
            relatedGoalId = json.stringOrNull("relatedGoalId"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun commitment(json: JSONObject): Commitment {
        val m = Meta(json)
        return Commitment(
            id = m.id,
            title = json.getString("title"),
            description = json.stringOrNull("description"),
            status = json.enumOr("status", CommitmentStatus.OPEN),
            dueAt = json.longOrNull("dueAt"),
            promisedTo = json.stringOrNull("promisedTo"),
            relatedTaskId = json.stringOrNull("relatedTaskId"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun openLoop(json: JSONObject): OpenLoop {
        val m = Meta(json)
        return OpenLoop(
            id = m.id,
            description = json.getString("description"),
            status = json.enumOr("status", OpenLoopStatus.OPEN),
            importance = json.optDouble("importance", 0.5),
            confidence = json.optDouble("confidence", 0.6),
            relatedEntityType = json.stringOrNull("relatedEntityType"),
            relatedEntityId = json.stringOrNull("relatedEntityId"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun routine(json: JSONObject): Routine {
        val m = Meta(json)
        return Routine(
            id = m.id,
            title = json.getString("title"),
            description = json.stringOrNull("description"),
            schedule = json.stringOrNull("schedule"),
            confidence = json.optDouble("confidence", 0.55),
            source = json.enumOr("source", MemorySource.AGENT_INFERENCE),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun grocery(json: JSONObject): GroceryItem {
        val m = Meta(json)
        return GroceryItem(
            id = m.id,
            name = json.getString("name"),
            quantity = json.stringOrNull("quantity"),
            category = json.stringOrNull("category"),
            status = json.enumOr("status", GroceryStatus.ACTIVE),
            reason = json.stringOrNull("reason"),
            recurrenceScore = json.optDouble("recurrenceScore", 0.0),
            notes = json.stringOrNull("notes"),
            store = json.stringOrNull("store"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun importantDate(json: JSONObject): ImportantDate {
        val m = Meta(json)
        return ImportantDate(
            id = m.id,
            title = json.getString("title"),
            dateIso = json.getString("dateIso"),
            recurrence = json.stringOrNull("recurrence"),
            relatedPersonId = json.stringOrNull("relatedPersonId"),
            notes = json.stringOrNull("notes"),
            importance = json.optDouble("importance", 0.7),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun responsibility(json: JSONObject): RecurringResponsibility {
        val m = Meta(json)
        return RecurringResponsibility(
            id = m.id,
            title = json.getString("title"),
            cadence = json.getString("cadence"),
            nextDueAt = json.longOrNull("nextDueAt"),
            lastCompletedAt = json.longOrNull("lastCompletedAt"),
            notes = json.stringOrNull("notes"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun calendarEvent(json: JSONObject): CalendarEvent {
        val m = Meta(json)
        return CalendarEvent(
            id = m.id,
            title = json.getString("title"),
            startAt = json.getLong("startAt"),
            endAt = json.longOrNull("endAt"),
            location = json.stringOrNull("location"),
            notes = json.stringOrNull("notes"),
            externalId = json.stringOrNull("externalId"),
            source = json.enumOr("source", CalendarSource.INTERNAL),
            calendarId = json.stringOrNull("calendarId"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun relationship(json: JSONObject): MemoryRelationship {
        val m = Meta(json)
        return MemoryRelationship(
            id = m.id,
            sourceType = json.getString("sourceType"),
            sourceId = json.getString("sourceId"),
            relationshipType = json.getString("relationshipType"),
            targetType = json.getString("targetType"),
            targetId = json.getString("targetId"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun agentState(json: JSONObject): AgentStateEntry {
        val m = Meta(json)
        return AgentStateEntry(
            id = m.id,
            kind = json.getString("kind"),
            content = json.getString("content"),
            confidence = json.optDouble("confidence", 0.5),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun agentQueue(json: JSONObject): AgentQueueItem {
        val m = Meta(json)
        return AgentQueueItem(
            id = m.id,
            description = json.getString("description"),
            status = json.enumOr("status", QueueStatus.OPEN),
            priority = json.optDouble("priority", 0.5),
            dueAt = json.longOrNull("dueAt"),
            relatedEntityType = json.stringOrNull("relatedEntityType"),
            relatedEntityId = json.stringOrNull("relatedEntityId"),
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            deviceId = m.deviceId,
            version = m.version,
            deletedAt = m.deletedAt,
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)

    private inline fun <reified T : Enum<T>> JSONObject.enumOr(key: String, default: T): T =
        stringOrNull(key)?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: default
}
