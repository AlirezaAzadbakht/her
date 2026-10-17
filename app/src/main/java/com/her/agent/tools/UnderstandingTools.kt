package com.her.agent.tools

import com.her.core.*
import com.her.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal val understandingToolNames = setOf("inspect_understanding", "correct_memory")

internal fun ToolRegistry.registerUnderstandingTools() {
    register("inspect_understanding", "Explain stored understanding of one topic, with source, date, confidence and historical status. Present facts versus interpretations in plain language. Ask which record if correction or forgetting is ambiguous. Page before broad changes; never promise physical erasure of chat or uploaded logs.",
        objSchema("query" to str(), "id" to str(), "offset" to num())) { a ->
        val query = a.optString("query").trim()
        val id = a.optString("id")
        if (query.isBlank() && id.isBlank()) throw ToolValidationException("Choose a topic or record")
        fun matches(key: String, content: String) = if (id.isNotBlank()) key == id else content.contains(query, true)
        val rows = mutableListOf<JSONObject>()
        suspend fun add(key: String, content: String, source: MemorySource, sourceId: String?, date: Long, status: String, version: Long) {
            if (!matches(key, content)) return
            val message = sourceId?.let { repo.getMessage(it) }?.takeIf { it.deletedAt == null }
            rows += jsonObjectOf("id" to key, "content" to content.take(700), "basis" to source.name,
                "status" to status, "updatedAt" to date, "sourceMessageId" to sourceId,
                "sourceDate" to message?.createdAt, "sourceQuote" to message?.content?.take(350), "version" to version)
        }
        repo.allLong().forEach { add(it.id, it.content, it.source, it.sourceMessageId, it.updatedAt, it.status.name, it.version) }
        repo.activeShort().forEach { add(it.id, it.content, it.source, it.sourceMessageId, it.updatedAt, "TEMPORARY", it.version) }
        repo.allUserUnderstandings().forEach { add(it.id, it.content, it.source, it.sourceMessageId, it.updatedAt, it.status.name, it.version) }
        val offset = a.optInt("offset", 0)
        if (offset < 0) throw ToolValidationException("Invalid offset")
        val page = rows.sortedByDescending { it.getLong("updatedAt") }.drop(offset).take(4)
        jsonObjectOf("ok" to true, "records" to JSONArray(page), "nextOffset" to if (offset + page.size < rows.size) offset + page.size else null,
            "scope" to "Stored memories and user understanding. Use get_life_context for linked situations; absence is not proof a fact is false.")
    }
    register("correct_memory", "Supersede one specific durable fact or understanding with quoted user evidence. Keeps the old fact historical and invalidates linked summaries. Inspect first; clarify ambiguous targets. Stable replacementId makes retries safe. This does not modify unrelated profile fields.",
        objSchema("id" to str(), "replacementId" to str(), "expectedVersion" to num(), "content" to str(), "sourceMessageId" to str(), "evidence" to str(),
            required = listOf("id", "replacementId", "expectedVersion", "content", "sourceMessageId", "evidence"))) { a ->
        repo.learningTransaction {
            val source = requireUserEvidence(a)
            val id = a.requiredString("id")
            val replacementId = "correction:${a.requiredString("replacementId").removePrefix("correction:")}"
            if (replacementId == id || replacementId.length > 180 || repo.knowledgeForgotten(replacementId)) throw ToolValidationException("Choose a new replacement ID")
            val content = a.requiredString("content")
            if (content.length > 1200) throw ToolValidationException("Keep correction focused")
            val existing = repo.getLong(replacementId)
            if (existing != null) {
                if (existing.content != content || existing.sourceMessageId != source.id || JSONObject(existing.metadataJson ?: "{}").optString("supersedes") != id)
                    throw ToolValidationException("Replacement ID already used")
                return@learningTransaction jsonObjectOf("ok" to true, "id" to replacementId, "created" to false)
            }
            val old = repo.getLong(id)?.takeIf { it.deletedAt == null }
            val understanding = repo.getUserUnderstanding(id)?.takeIf { it.deletedAt == null }
            val short = repo.getShort(id)?.takeIf { it.deletedAt == null }
            val version = old?.version ?: understanding?.version ?: short?.version ?: throw ToolValidationException("Memory not found")
            if (version != a.getLong("expectedVersion")) throw ToolValidationException("Read current memory before correcting")
            val now = nowMillis()
            if (old != null) repo.upsertLong(old.copy(status = MemoryStatus.HISTORICAL, validUntil = now, updatedAt = now, version = old.version + 1))
            if (understanding != null) repo.saveUserUnderstanding(understanding.copy(status = MemoryStatus.HISTORICAL, updatedAt = now, version = version + 1))
            if (short != null) {
                repo.upsertLong(LongTermMemory("history:$id", short.content, short.type, short.confidence, short.importance, short.createdAt, now,
                    null, short.source, short.sourceMessageId, JSONArray().put(id).toString(), short.createdAt, now, MemoryStatus.HISTORICAL, null, repo.deviceId, 1, null))
                repo.upsertShort(short.copy(expiresAt = now, updatedAt = now, version = version + 1))
            }
            val markerId = "superseded:$id"
            repo.saveCompanionRecord(com.her.data.db.CompanionRecord(markerId, markerId, "SUPERSESSION", jsonObjectOf("targetId" to id).toString(), now, now, repo.deviceId, 1))
            repo.invalidateKnowledge(id)
            repo.upsertLong(LongTermMemory(replacementId, content, old?.category ?: "correction", 1.0, old?.importance ?: 0.7,
                now, now, now, MemorySource.USER_EXPLICIT, source.id, null, now, null, MemoryStatus.ACTIVE,
                jsonObjectOf("supersedes" to id, "evidence" to a.getString("evidence")).toString(), repo.deviceId, 1, null))
            jsonObjectOf("ok" to true, "id" to replacementId, "historicalId" to id, "created" to true, "externalActionPerformed" to false)
        }
    }
}

internal suspend fun ToolRegistry.requireUserEvidence(a: JSONObject): ChatMessage {
    val source = repo.getMessage(a.requiredString("sourceMessageId"))?.takeIf { it.role == MessageRole.USER && it.deletedAt == null }
        ?: throw ToolValidationException("An existing user message is required")
    if (a.requiredString("evidence").length > 600) throw ToolValidationException("Quote at most 600 characters")
    if (!source.content.contains(a.requiredString("evidence"))) throw ToolValidationException("Evidence must quote the user message")
    return source
}
