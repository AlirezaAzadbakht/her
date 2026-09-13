package com.her.agent.tools

import com.her.core.COMMON_DONE
import com.her.core.COMMON_DROPPED
import com.her.core.ToolValidationException
import com.her.core.clamp01
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.optDoubleOr
import com.her.core.optStringOrNull
import com.her.core.parseEnum
import com.her.core.requiredString
import com.her.data.repository.toJson
import com.her.domain.AgentQueueItem
import com.her.domain.AgentStateEntry
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.QueueStatus
import com.her.domain.UserUnderstanding
import com.her.agent.prompt.Identity
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.registerAgentTools() {
    register("get_agent_state", "Read private working notes.", objSchema()) { jsonObjectOf("ok" to true, "state" to JSONArray(repo.agentState().map { JSONObject(it.toJson()) })) }
    register("update_agent_state", "Write a private working note. Fluid and temporary. kind=digest is the one rolling note about recent days; writing it replaces the previous digest.", objSchema("id" to str(), "kind" to str("note, digest, or another short label"), "content" to str(), "confidence" to num(), required = listOf("kind", "content"))) { args ->
        val now = nowMillis()
        val existing = args.optStringOrNull("id")?.let { repo.getAgentState(it) }
            ?: args.optStringOrNull("kind")?.takeIf { it == Identity.DIGEST_KIND }?.let { kind ->
                repo.agentState().firstOrNull { it.kind == kind }
            }
        val item = (existing ?: AgentStateEntry(newId(), args.requiredString("kind"), args.requiredString("content"), 0.5, now, now, repo.deviceId, 1, null)).copy(
            kind = args.optStringOrNull("kind") ?: existing?.kind ?: "note",
            content = args.optStringOrNull("content") ?: existing?.content.orEmpty(),
            confidence = args.optDoubleOr("confidence", existing?.confidence ?: 0.5),
            updatedAt = now,
            version = (existing?.version ?: 0) + 1,
        )
        repo.saveAgentState(item)
        jsonObjectOf("ok" to true, "id" to item.id)
    }
    register("get_agent_queue", "Read the assistant's private todo queue.", objSchema()) { jsonObjectOf("ok" to true, "queue" to JSONArray(repo.agentQueue().map { JSONObject(it.toJson()) })) }
    register("add_agent_queue_item", "Add a private assistant todo.", objSchema("description" to str(), "priority" to num(), "dueAt" to str(), required = listOf("description"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveAgentQueue(AgentQueueItem(id, args.requiredString("description"), QueueStatus.OPEN, args.optDoubleOr("priority", 0.5), parseWhen(args.optStringOrNull("dueAt")), args.optStringOrNull("relatedEntityType"), args.optStringOrNull("relatedEntityId"), now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
    register("update_agent_queue_item", "Update a queue item.", objSchema("id" to str(), "status" to enumField<QueueStatus>(), "description" to str(), required = listOf("id"))) { args ->
        val existing = repo.getAgentQueue(args.requiredString("id")) ?: throw ToolValidationException("Queue item not found")
        repo.saveAgentQueue(existing.copy(description = args.optStringOrNull("description") ?: existing.description, status = parseEnum<QueueStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED) ?: existing.status, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }
    register("complete_agent_queue_item", "Complete a queue item.", objSchema("id" to str(), required = listOf("id"))) { args ->
        val existing = repo.getAgentQueue(args.requiredString("id")) ?: throw ToolValidationException("Queue item not found")
        repo.saveAgentQueue(existing.copy(status = QueueStatus.DONE, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }
    register(
        "update_user_understanding",
        "Create or revise what you know about who this person is. Use this for how they communicate, how they want help, what they are going through, and recurring patterns — not one-off facts (remember) or profile logistics (update_user_profile). If they share how they want help AND what they are going through, write help_style and life_chapter as separate calls. Upsert by id or by facet so each facet has one ACTIVE row. Mark stale rows HISTORICAL instead of deleting. Do not diagnose personality or mental health.",
        objSchema(
            "id" to str("Existing row id from the About them section"),
            "facet" to oneOf("Which part of them this describes", "communication", "help_style", "life_chapter", "values", "patterns", "relationship_to_her", "other"),
            "content" to str("What you understand about them"),
            "confidence" to num("0-1"),
            "importance" to num("0-1"),
            "status" to enumField<MemoryStatus>(),
            "source" to oneOf("USER_EXPLICIT when they said it, AGENT_INFERENCE when you inferred it", "USER_EXPLICIT", "AGENT_INFERENCE"),
            "sourceMessageId" to str("Origin message id"),
        ),
    ) { args ->
        val now = nowMillis()
        val requestedId = args.optStringOrNull("id")
        val facet = UserUnderstanding.normalizeFacet(args.optStringOrNull("facet"))
        val existing = requestedId?.let { repo.getUserUnderstanding(it) }
            ?: repo.activeUserUnderstandingByFacet(facet)
        val content = args.optStringOrNull("content") ?: existing?.content
            ?: throw ToolValidationException("Missing required field: content")
        val item = (existing ?: UserUnderstanding(
            id = newId(),
            facet = facet,
            content = content,
            confidence = 0.7,
            importance = 0.6,
            source = MemorySource.AGENT_INFERENCE,
            sourceMessageId = null,
            status = MemoryStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
        )).copy(
            facet = if (args.optStringOrNull("facet") != null) facet else existing?.facet ?: facet,
            content = content,
            confidence = clamp01(args.optDoubleOr("confidence", existing?.confidence ?: 0.7)),
            importance = clamp01(args.optDoubleOr("importance", existing?.importance ?: 0.6)),
            source = parseEnum<MemorySource>(args.optStringOrNull("source")) ?: existing?.source ?: MemorySource.AGENT_INFERENCE,
            sourceMessageId = args.optStringOrNull("sourceMessageId") ?: existing?.sourceMessageId,
            status = parseEnum<MemoryStatus>(args.optStringOrNull("status")) ?: existing?.status ?: MemoryStatus.ACTIVE,
            updatedAt = now,
            version = (existing?.version ?: 0) + 1,
        )
        repo.saveUserUnderstanding(item)
        jsonObjectOf("ok" to true, "id" to item.id, "facet" to item.facet)
    }
    register("update_user_profile", "Update high-value profile fields when the person explicitly shares them.", objSchema("userName" to str(), "assistantName" to str(), "timezone" to str(), "preferredLanguage" to str(), "country" to str(), "typicalWakeTime" to str(), "typicalSleepTime" to str(), "occupationOrStudyContext" to str())) { args ->
        val current = repo.getProfile()
        repo.saveProfile(
            current.copy(
                userName = args.optStringOrNull("userName") ?: current.userName,
                assistantName = args.optStringOrNull("assistantName") ?: current.assistantName,
                timezone = args.optStringOrNull("timezone") ?: current.timezone,
                preferredLanguage = args.optStringOrNull("preferredLanguage") ?: current.preferredLanguage,
                country = args.optStringOrNull("country") ?: current.country,
                typicalWakeTime = args.optStringOrNull("typicalWakeTime") ?: current.typicalWakeTime,
                typicalSleepTime = args.optStringOrNull("typicalSleepTime") ?: current.typicalSleepTime,
                occupationOrStudyContext = args.optStringOrNull("occupationOrStudyContext") ?: current.occupationOrStudyContext,
                updatedAt = nowMillis(),
                version = current.version + 1,
            ),
        )
        jsonObjectOf("ok" to true)
    }
}
