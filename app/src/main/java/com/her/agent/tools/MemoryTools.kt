package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.clamp01
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.optDoubleOr
import com.her.core.optLongOrNull
import com.her.core.optStringList
import com.her.core.optStringOrNull
import com.her.core.parseEnum
import com.her.core.requiredString
import com.her.domain.ConfirmationKind
import com.her.domain.LongTermMemory
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.PendingConfirmation
import com.her.domain.ShortTermMemory
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.registerMemoryTools() {
    register(
        "get_current_time",
        "Return the current local date and time.",
        objSchema(),
    ) {
        val profile = repo.getProfile()
        val zone = runCatching { ZoneId.of(profile.timezone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
        val now = Instant.ofEpochMilli(nowMillis()).atZone(zone)
        jsonObjectOf(
            "ok" to true,
            "iso" to now.toOffsetDateTime().toString(),
            "zone" to zone.id,
            "epochMillis" to nowMillis(),
        )
    }

    register(
        "search_memory",
        "Search short-term and long-term memories using text relevance, importance, confidence, and recency.",
        objSchema(
            "query" to str("Search query"),
            "memoryTypes" to arr("Optional: short_term, long_term"),
            "limit" to num("Max results"),
            required = listOf("query"),
        ),
    ) { args ->
        val hits = ranker.search(
            query = args.requiredString("query"),
            memoryTypes = args.optStringList("memoryTypes"),
            limit = args.optInt("limit", 8),
        )
        jsonObjectOf("ok" to true, "results" to JSONArray().apply {
            hits.forEach { hit ->
                put(
                    JSONObject()
                        .put("id", hit.id)
                        .put("type", hit.memoryType)
                        .put("content", hit.content)
                        .put("score", hit.score)
                        .put("importance", hit.importance)
                        .put("confidence", hit.confidence)
                        .put("source", hit.source)
                        .put("sourceMessageId", hit.sourceMessageId)
                        .put("derivedFrom", hit.derivedFromJson),
                )
            }
        })
    }

    register(
        "remember",
        "Store a fact, preference, or household spec. Use short_term for temporary/uncertain context; long_term for durable facts. Do not use this for todos — that is create_task or add_grocery.",
        objSchema(
            "content" to str("Memory text"),
            "scope" to oneOf("short_term for temporary context, long_term for durable facts", "short_term", "long_term"),
            "type" to str("Optional type/category"),
            "confidence" to num("0-1"),
            "importance" to num("0-1"),
            "source" to oneOf("USER_EXPLICIT when they said it, AGENT_INFERENCE when you inferred it", "USER_EXPLICIT", "AGENT_INFERENCE"),
            "sourceMessageId" to str("Origin message id"),
            "expiresAt" to num("Optional expiry epoch millis"),
            required = listOf("content"),
        ),
    ) { args ->
        val scope = args.optString("scope", "short_term")
        val source = runCatching { MemorySource.valueOf(args.optString("source", "AGENT_INFERENCE")) }.getOrDefault(MemorySource.AGENT_INFERENCE)
        val id = newId()
        val now = nowMillis()
        if (scope == "long_term") {
            repo.upsertLong(
                LongTermMemory(
                    id = id,
                    content = args.requiredString("content"),
                    category = args.optString("type", "general"),
                    confidence = clamp01(args.optDoubleOr("confidence", 0.7)),
                    importance = clamp01(args.optDoubleOr("importance", 0.5)),
                    createdAt = now,
                    updatedAt = now,
                    lastConfirmedAt = if (source == MemorySource.USER_EXPLICIT) now else null,
                    source = source,
                    sourceMessageId = args.optStringOrNull("sourceMessageId"),
                    derivedFromJson = args.optStringOrNull("derivedFrom"),
                    validFrom = now,
                    validUntil = null,
                    status = MemoryStatus.ACTIVE,
                    metadataJson = null,
                    deviceId = repo.deviceId,
                    version = 1,
                    deletedAt = null,
                ),
            )
        } else {
            repo.upsertShort(
                ShortTermMemory(
                    id = id,
                    content = args.requiredString("content"),
                    type = args.optString("type", "context"),
                    confidence = clamp01(args.optDoubleOr("confidence", 0.6)),
                    importance = clamp01(args.optDoubleOr("importance", 0.4)),
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = args.optLongOrNull("expiresAt"),
                    sourceMessageId = args.optStringOrNull("sourceMessageId"),
                    source = source,
                    metadataJson = null,
                    deviceId = repo.deviceId,
                    version = 1,
                    deletedAt = null,
                ),
            )
        }
        jsonObjectOf("ok" to true, "id" to id, "scope" to scope)
    }

    register(
        "update_memory",
        "Update an existing short-term or long-term memory. Use status=HISTORICAL instead of deleting old facts. scope=long_term on a short-term memory promotes it to long-term.",
        objSchema(
            "id" to str("Memory id"),
            "scope" to oneOf("short_term for temporary context, long_term for durable facts", "short_term", "long_term"),
            "content" to str("Replacement text"),
            "confidence" to num(),
            "importance" to num(),
            "status" to enumField<MemoryStatus>(),
            required = listOf("id"),
        ),
    ) { args ->
        val id = args.requiredString("id")
        val now = nowMillis()
        val short = repo.getShort(id)
        val long = repo.getLong(id)
        when {
            short != null && args.optString("scope") == "long_term" -> {
                val longId = newId()
                repo.upsertLong(
                    LongTermMemory(
                        id = longId,
                        content = args.optStringOrNull("content") ?: short.content,
                        category = short.type,
                        confidence = clamp01(args.optDoubleOr("confidence", short.confidence)),
                        importance = clamp01(args.optDoubleOr("importance", short.importance)),
                        createdAt = short.createdAt,
                        updatedAt = now,
                        lastConfirmedAt = if (short.source == MemorySource.USER_EXPLICIT) now else null,
                        source = short.source,
                        sourceMessageId = short.sourceMessageId,
                        derivedFromJson = null,
                        validFrom = now,
                        validUntil = null,
                        status = MemoryStatus.ACTIVE,
                        metadataJson = null,
                        deviceId = repo.deviceId,
                        version = 1,
                        deletedAt = null,
                    ),
                )
                repo.deleteShort(short.id)
                return@register jsonObjectOf("ok" to true, "id" to longId, "promotedFrom" to short.id)
            }
            short != null -> {
                repo.upsertShort(
                    short.copy(
                        content = args.optStringOrNull("content") ?: short.content,
                        confidence = clamp01(args.optDoubleOr("confidence", short.confidence)),
                        importance = clamp01(args.optDoubleOr("importance", short.importance)),
                        updatedAt = now,
                        version = short.version + 1,
                    ),
                )
            }
            long != null -> {
                repo.upsertLong(
                    long.copy(
                        content = args.optStringOrNull("content") ?: long.content,
                        confidence = clamp01(args.optDoubleOr("confidence", long.confidence)),
                        importance = clamp01(args.optDoubleOr("importance", long.importance)),
                        status = parseEnum<MemoryStatus>(args.optStringOrNull("status")) ?: long.status,
                        updatedAt = now,
                        version = long.version + 1,
                    ),
                )
            }
            else -> throw ToolValidationException("Memory not found: $id")
        }
        jsonObjectOf("ok" to true, "id" to id)
    }

    register(
        "forget_memory",
        "Forget one memory, or request bulk forget (requires confirmation).",
        objSchema(
            "id" to str("Memory id"),
            "everything" to bool("If true, request confirmation to forget all personal knowledge"),
            "confirmId" to str("Confirmation id from a previous request"),
            required = emptyList(),
        ),
    ) { args ->
        if (args.optBoolean("everything")) {
            val confirmId = args.optStringOrNull("confirmId")
            if (confirmId == null) {
                val pending = PendingConfirmation(newId(), ConfirmationKind.BULK_FORGET, "Forget all memories and what Her understands about you", "{}", nowMillis())
                repo.saveConfirmation(pending)
                return@register jsonObjectOf("ok" to false, "needsConfirmation" to true, "confirmId" to pending.id, "summary" to pending.summary)
            }
            val pending = repo.getConfirmation(confirmId)?.takeIf { it.kind == ConfirmationKind.BULK_FORGET }
                ?: throw ToolValidationException("Confirmation not found")
            repo.deleteConfirmation(confirmId)
            repo.allLong().forEach { repo.deleteLong(it.id) }
            repo.activeShort().forEach { repo.deleteShort(it.id) }
            repo.activeUserUnderstandings().forEach {
                repo.saveUserUnderstanding(it.copy(status = MemoryStatus.ARCHIVED, updatedAt = nowMillis(), version = it.version + 1))
            }
            repo.logActivity("memory", "Bulk forget confirmed ${pending.id}")
            return@register jsonObjectOf("ok" to true, "forgotten" to "all")
        }
        val id = args.requiredString("id")
        when {
            repo.getShort(id) != null -> repo.deleteShort(id)
            repo.getLong(id) != null -> repo.deleteLong(id)
            else -> throw ToolValidationException("Memory not found: $id")
        }
        jsonObjectOf("ok" to true, "id" to id)
    }

    register("search_chat_history", "Search the lifetime conversation.", objSchema("query" to str(), "limit" to num(), required = listOf("query"))) { args ->
        val found = repo.searchChat(args.requiredString("query"), args.optInt("limit", 10))
        jsonObjectOf("ok" to true, "messages" to JSONArray().apply {
            found.forEach { put(JSONObject().put("id", it.id).put("role", it.role.name).put("content", it.content).put("createdAt", it.createdAt)) }
        })
    }

}
