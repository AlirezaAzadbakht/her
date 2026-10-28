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
            "query" to str(),
            "memoryTypes" to arr("Optional: short_term, long_term"),
            "limit" to num(),
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
                        .put("updatedAt", hit.updatedAt)
                        .put("status", "ACTIVE")
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
        "Store a fact they asked you to remember, including where they work. Not a todo, and not a profile field.",
        objSchema(
            "content" to str(),
            "scope" to oneOf("short_term for temporary context, long_term for durable facts", "short_term", "long_term"),
            "type" to str(),
            "confidence" to num("0-1"),
            "importance" to num("0-1"),
            "source" to oneOf("USER_EXPLICIT when they said it, AGENT_INFERENCE when you inferred it", "USER_EXPLICIT", "AGENT_INFERENCE"),
            "sourceMessageId" to str(),
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
        "Update a memory; HISTORICAL keeps the old fact, and long_term promotes a short-term one.",
        objSchema(
            "id" to str(),
            "scope" to oneOf("short_term for temporary context, long_term for durable facts", "short_term", "long_term"),
            "content" to str(),
            "confidence" to num(),
            "importance" to num(),
            "status" to enumField<MemoryStatus>(),
            required = listOf("id"),
        ),
    ) { args ->
        val id = args.requiredString("id")
        val now = nowMillis()
        val short = repo.getShort(id)?.takeIf { it.deletedAt == null }
        val long = repo.getLong(id)?.takeIf { it.deletedAt == null }
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
            "id" to str(),
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
            val pending = consumeConfirmation(confirmId, ConfirmationKind.BULK_FORGET)
            repo.companionRecords().filter { it.kind !in setOf("FORGOTTEN", "SUPERSESSION", "DELIVERY") }.forEach { repo.forgetCompanion(it.id) }
            repo.wonderings().forEach { repo.forgetKnowledge(it.id) }
            repo.allLong().forEach { repo.deleteLong(it.id) }
            repo.allShort().forEach { repo.deleteShort(it.id) }
            repo.allUserUnderstandings().forEach { repo.forgetKnowledge(it.id) }
            repo.logActivity("memory", "Bulk forget confirmed ${pending.id}")
            return@register jsonObjectOf("ok" to true, "forgotten" to "all")
        }
        val id = args.requiredString("id")
        val linked = mutableListOf<String>()
        when {
            repo.getWondering(id) != null -> repo.forgetKnowledge(id)
            repo.getUserUnderstanding(id) != null -> repo.forgetKnowledge(id)
            repo.getCompanionRecord(id)?.kind in setOf("FORGOTTEN", "SUPERSESSION", "DELIVERY") -> throw ToolValidationException("Internal deletion/delivery marker cannot be removed")
            repo.getCompanionRecord(id) != null -> linked += repo.forgetCompanion(id)
            repo.getShort(id) != null -> repo.deleteShort(id)
            repo.getLong(id) != null -> repo.deleteLong(id)
            else -> throw ToolValidationException("Memory not found: $id")
        }
        jsonObjectOf("ok" to true, "id" to id).apply { if (linked.isNotEmpty()) put("alsoForgotten", JSONArray(linked)) }
    }

    register(
        "save_episode",
        "Nightly: save a short dated note of one day, or roll up the episodes listed in rollUpDue. The same period and start replaces it.",
        objSchema(
            "period" to oneOf("day, or week/month for a roll-up", "day", "week", "month"),
            "start" to str("yyyy-MM-dd: the day, the Monday of the week, or the 1st of the month"),
            "content" to str("At most 80 words in their language: what happened, who was involved, how it went for them, what is still open. Not your own feelings."),
            "importance" to num("0-1"),
            required = listOf("period", "start", "content"),
        ),
    ) { args -> saveEpisode(args) }

    register("search_chat_history", "Search the lifetime conversation.", objSchema("query" to str(), "limit" to num(), required = listOf("query"))) { args ->
        val found = repo.searchChat(args.requiredString("query"), args.optInt("limit", 10))
        jsonObjectOf("ok" to true, "messages" to JSONArray().apply {
            found.forEach { put(JSONObject().put("id", it.id).put("role", it.role.name).put("content", it.content).put("createdAt", it.createdAt)) }
        })
    }

}

private const val EPISODE_DAY_MS = 24L * 60 * 60 * 1000
private const val DAYS_BEFORE_WEEKLY = 14L
private const val DAYS_BEFORE_MONTHLY = 60L

/** Recent days stay detailed; older ones are compressed into weeks, and weeks into months, like human memory. */
private suspend fun ToolRegistry.saveEpisode(args: JSONObject): JSONObject {
    val period = args.requiredString("period").lowercase()
    if (period !in setOf("day", "week", "month")) throw ToolValidationException("period must be day, week or month")
    val start = runCatching { java.time.LocalDate.parse(args.requiredString("start")) }.getOrNull()
        ?: throw ToolValidationException("start must be yyyy-MM-dd")
    val content = args.requiredString("content").trim()
    if (content.isBlank()) throw ToolValidationException("content must not be empty")
    if (content.split(Regex("\\s+")).size > 100) throw ToolValidationException("Keep an episode under 80 words")
    val zone = repo.profileZone()
    val today = java.time.LocalDate.now(java.time.Clock.fixed(Instant.ofEpochMilli(nowMillis()), zone))
    val aligned = when (period) {
        "week" -> start.with(java.time.DayOfWeek.MONDAY)
        "month" -> start.withDayOfMonth(1)
        else -> start
    }
    if (aligned != start) throw ToolValidationException("A $period starts on $aligned")
    val end = when (period) {
        "week" -> start.plusWeeks(1)
        "month" -> start.plusMonths(1)
        else -> start.plusDays(1)
    }
    if (start > today) throw ToolValidationException("Cannot save an episode for the future")
    if (period != "day" && end > today) throw ToolValidationException("Roll up a $period only after it has ended")
    val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val until = end.atStartOfDay(zone).toInstant().toEpochMilli()
    val childPeriod = if (period == "month") "week" else "day"
    val children = if (period == "day") emptyList() else liveEpisodes().filter {
        episodePeriod(it) == childPeriod && it.validFrom != null && it.validFrom in from until until
    }
    // Derived links let existing forgetting remove the episode along with what it retells.
    val dependencies = if (period == "day") {
        repo.userMessagesBetween(from, until).map { it.id }.ifEmpty {
            return jsonObjectOf("ok" to true, "saved" to false, "reason" to "No conversation on $start; nothing to remember")
        }
    } else {
        children.ifEmpty { throw ToolValidationException("No $childPeriod episodes to roll up for this $period") }
        children.flatMap { child -> listOf(child.id) + derivedIds(child) }.distinct()
    }
    val id = "episode:$period:$start"
    if (repo.knowledgeForgotten(id)) throw ToolValidationException("They asked you to forget part of this $period; do not write it again")
    val existing = repo.getLong(id)
    val now = nowMillis()
    repo.upsertLong(
        LongTermMemory(
            id = id,
            content = content,
            category = com.her.data.repository.EPISODE_CATEGORY,
            confidence = 0.7,
            importance = clamp01(args.optDoubleOr("importance", 0.5)),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastConfirmedAt = existing?.lastConfirmedAt,
            source = MemorySource.AGENT_INFERENCE,
            sourceMessageId = null,
            derivedFromJson = JSONArray(dependencies).toString(),
            validFrom = from,
            validUntil = until,
            // A day already folded into a week stays folded.
            status = if (existing?.status == MemoryStatus.ARCHIVED) MemoryStatus.ARCHIVED else MemoryStatus.ACTIVE,
            metadataJson = JSONObject().put("period", period).put("start", start.toString()).toString(),
            deviceId = repo.deviceId,
            version = (existing?.version ?: 0) + 1,
            deletedAt = null,
        ),
    )
    // Archived children leave everyday recall but stay inspectable.
    children.filter { it.status == MemoryStatus.ACTIVE }.forEach {
        repo.upsertLong(it.copy(status = MemoryStatus.ARCHIVED, updatedAt = now, version = it.version + 1))
    }
    return jsonObjectOf("ok" to true, "id" to id, "rolledUp" to JSONArray(children.map { it.id }), "rollUpDue" to rollUpDue(today, zone))
}

/**
 * Recent days with conversation and no up-to-date episode. The nightly pass runs at 03:00, so the day that
 * just ended is usually yesterday; three days also cover nights the phone missed.
 */
internal suspend fun daysToRemember(repo: com.her.data.repository.HerRepository): List<java.time.LocalDate> {
    val zone = repo.profileZone()
    val today = repo.today()
    return (3L downTo 0L).map { today.minusDays(it) }.filter { day ->
        val messages = repo.userMessagesBetween(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val id = "episode:day:$day"
        val episode = repo.getLong(id)
        messages.isNotEmpty() && !repo.knowledgeForgotten(id) && (episode == null || episode.updatedAt < messages.last().createdAt)
    }
}

private suspend fun ToolRegistry.liveEpisodes(): List<LongTermMemory> =
    repo.allLong().filter { it.deletedAt == null && it.category == com.her.data.repository.EPISODE_CATEGORY }

private fun episodePeriod(memory: LongTermMemory): String =
    runCatching { JSONObject(memory.metadataJson ?: "{}").optString("period") }.getOrDefault("")

private fun derivedIds(memory: LongTermMemory): List<String> {
    val array = runCatching { JSONArray(memory.derivedFromJson ?: "[]") }.getOrNull() ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}

/** Up to two roll-ups to write next: active days older than two weeks, and weeks older than two months. */
private suspend fun ToolRegistry.rollUpDue(today: java.time.LocalDate, zone: ZoneId): JSONArray {
    val episodes = liveEpisodes().filter { it.status == MemoryStatus.ACTIVE && it.validFrom != null }
    fun dayOf(memory: LongTermMemory) = Instant.ofEpochMilli(memory.validFrom!!).atZone(zone).toLocalDate()
    val weeks = episodes.filter { episodePeriod(it) == "day" && dayOf(it) < today.minusDays(DAYS_BEFORE_WEEKLY) }
        .groupBy { "week" to dayOf(it).with(java.time.DayOfWeek.MONDAY) }
    val months = episodes.filter { episodePeriod(it) == "week" && dayOf(it) < today.minusDays(DAYS_BEFORE_MONTHLY) }
        .groupBy { "month" to dayOf(it).withDayOfMonth(1) }
    return JSONArray().apply {
        (weeks + months).entries.sortedBy { it.key.second }.take(2).forEach { (key, items) ->
            put(JSONObject().put("period", key.first).put("start", key.second.toString())
                .put("episodes", JSONArray(items.sortedBy { it.validFrom }.map { "${it.id}: ${it.content}" })))
        }
    }
}
