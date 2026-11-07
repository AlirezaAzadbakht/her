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
import com.her.data.db.NIGHTLY_DRIFT
import com.her.data.db.REQUEST_LIMIT
import com.her.data.db.TRAITS
import com.her.data.db.clampTrait
import com.her.data.repository.toJson
import com.her.domain.AgentQueueItem
import com.her.domain.AgentRunType
import com.her.domain.AgentStateEntry
import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.QueueStatus
import com.her.domain.UserUnderstanding
import com.her.agent.prompt.Identity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal fun ToolRegistry.registerAgentTools() {
    register("get_agent_state", "Read private working notes.", objSchema()) { jsonObjectOf("ok" to true, "state" to JSONArray(repo.agentState().map { JSONObject(it.toJson()) })) }
    register("update_agent_state", "Write a private note; kind=digest replaces the rolling note, and content must be nonempty.", objSchema("id" to str(), "kind" to str("note, digest, or another short label"), "content" to str("Nonempty. Use No retained notes. when nothing remains."), "confidence" to num(), required = listOf("kind", "content"))) { args ->
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
    register(
        "set_feeling",
        "Record your emotional tone when this conversation genuinely shifts it.",
        objSchema(
            "feeling" to enumField<Feeling>(),
            "intensity" to num("0-1"),
            "note" to str("A few words on why"),
            "sourceMessageId" to str("Message that moved you"),
            required = listOf("feeling"),
        ),
    ) { args ->
        val raw = args.requiredString("feeling")
        val feeling = parseEnum<Feeling>(raw) ?: throw ToolValidationException("Unknown feeling: $raw")
        val now = nowMillis()
        val item = HerFeeling(
            id = newId(),
            feeling = feeling,
            intensity = clamp01(args.optDoubleOr("intensity", 0.5)),
            note = args.optStringOrNull("note"),
            sourceMessageId = args.optStringOrNull("sourceMessageId"),
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
        )
        repo.saveFeeling(item)
        jsonObjectOf("ok" to true, "id" to item.id, "feeling" to feeling.name)
    }
    register("get_agent_queue", "Read the assistant's private todo queue.", objSchema()) { jsonObjectOf("ok" to true, "queue" to JSONArray(repo.agentQueue().map { JSONObject(it.toJson()) })) }
    register("add_agent_queue_item", "Add a private assistant todo.", objSchema("description" to str(), "priority" to num(), "dueAt" to str(), required = listOf("description"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveAgentQueue(AgentQueueItem(id, args.requiredString("description"), QueueStatus.OPEN, args.optDoubleOr("priority", 0.5), parseDeadline(args.optStringOrNull("dueAt")), args.optStringOrNull("relatedEntityType"), args.optStringOrNull("relatedEntityId"), now, now, repo.deviceId, 1, null))
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
        "Create or revise one facet of who they are, so each facet has a single active row.",
        objSchema(
            "id" to str("Existing row id from the About them section"),
            "facet" to oneOf("", "communication", "help_style", "life_chapter", "values", "patterns", "relationship_to_her", "other"),
            "content" to str(),
            "confidence" to num("0-1"),
            "importance" to num("0-1"),
            "status" to enumField<MemoryStatus>(),
            "source" to oneOf("USER_EXPLICIT when they said it, AGENT_INFERENCE when you inferred it", "USER_EXPLICIT", "AGENT_INFERENCE"),
            "sourceMessageId" to str(),
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
    register("update_user_profile", "Update profile logistics they explicitly share. Where they work, when they say to remember it, is remember.", objSchema("userName" to str(), "assistantName" to str(), "timezone" to str(), "preferredLanguage" to str(), "country" to str(), "typicalWakeTime" to str(), "typicalSleepTime" to str(), "occupationOrStudyContext" to str())) { args ->
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
    register("adjust_trait", "Change one of your own personality traits: in chat only when they ask you to be different, in the nightly review a small drift from today.",
        objSchema("trait" to oneOf("", *TRAITS.toTypedArray()), "change" to num("-0.3 to 0.3; nightly at most 0.03"),
            "reason" to str("Short, in your words"), "sourceMessageId" to str("In chat: the user message that asked"),
            required = listOf("trait", "change", "reason"))) { a ->
        val trait = a.requiredString("trait")
        if (trait !in TRAITS) throw ToolValidationException("Unknown trait: $trait")
        val change = a.optDouble("change")
        if (change.isNaN() || change == 0.0 || abs(change) > REQUEST_LIMIT) throw ToolValidationException("change must be between -0.3 and 0.3, not 0")
        val reason = a.requiredString("reason").take(200)
        val row = repo.persona().first { it.trait == trait }
        val now = nowMillis()
        val saved = when (runType) {
            // Their request sets where she is heading; the nightly step gets her there over a few days.
            AgentRunType.CHAT -> {
                val asked = a.optStringOrNull("sourceMessageId")?.let { repo.getMessage(it) }
                if (asked?.role != MessageRole.USER) throw ToolValidationException("Only their own request changes you; pass the id of the user message that asked")
                row.copy(target = clampTrait((row.target ?: row.value) + change), reason = reason, updatedAt = now)
            }
            AgentRunType.NIGHTLY -> {
                if (row.target != null) throw ToolValidationException("You are working on $trait because they asked; leave it")
                if (row.reason != null && now - row.updatedAt < 12 * 3_600_000L) throw ToolValidationException("$trait already changed today")
                row.copy(value = clampTrait(row.value + change.coerceIn(-NIGHTLY_DRIFT, NIGHTLY_DRIFT)), reason = reason, updatedAt = now)
            }
            else -> throw ToolValidationException("Traits change only in chat or the nightly review")
        }
        repo.savePersonaTrait(saved)
        jsonObjectOf("ok" to true, "trait" to trait, "value" to saved.value, "target" to saved.target)
    }
}
