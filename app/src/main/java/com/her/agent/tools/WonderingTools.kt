package com.her.agent.tools

import com.her.core.*
import com.her.data.db.WonderingRecord
import com.her.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal val wonderingToolNames = setOf("review_wonderings", "save_wondering", "ask_wondering")
private const val DAY = 86_400_000L

internal fun ToolRegistry.registerWonderingTools() {
    register("review_wonderings", "Revisit overdue tasks, old purchases and a rotating page of memories; list saved questions. Observations are not explanations.", objSchema("sourceId" to str("Optional exact sourceId from a saved record; omit for general review. Not a date or run ID."), "offset" to num())) { a ->
        val offset = a.optInt("offset", 0)
        if (offset < 0) throw ToolValidationException("Invalid offset")
        val now = nowMillis()
        repo.wonderings().filter { it.status in setOf("OPEN", "ASKED") }.forEach { row ->
            if (repo.wonderingSource(row.sourceId) == null) repo.saveWondering(row.copy(status = "OBSOLETE", updatedAt = now, version = row.version + 1))
        }
        val candidates = mutableListOf<JSONObject>()
        repo.tasks().filter { it.status == TaskStatus.OPEN && it.dueAt != null && it.dueAt < now - DAY }
            .sortedBy { it.dueAt }.take(8).forEach {
                candidates += jsonObjectOf("sourceId" to it.id, "type" to "task", "content" to it.title, "dueAt" to it.dueAt, "updatedAt" to it.updatedAt)
            }
        repo.groceries().filter { it.status == GroceryStatus.ACTIVE && it.createdAt < now - 7 * DAY }
            .sortedBy { it.createdAt }.take(8).forEach {
                candidates += jsonObjectOf("sourceId" to it.id, "type" to "purchase", "content" to it.name, "createdAt" to it.createdAt, "updatedAt" to it.updatedAt)
            }
        val memories = repo.allLong().filter { it.status == MemoryStatus.ACTIVE }.sortedBy { it.id }
        if (memories.isNotEmpty()) {
            val start = ((now / DAY * 4) % memories.size).toInt()
            repeat(minOf(4, memories.size)) { offset ->
                val m = memories[(start + offset) % memories.size]
                candidates += jsonObjectOf("sourceId" to m.id, "type" to "memory", "content" to m.content.take(800), "updatedAt" to m.updatedAt)
            }
        }
        val rows = repo.wonderings().filter { a.optString("sourceId").isBlank() || it.sourceId == a.optString("sourceId") }
            .sortedWith(compareByDescending<WonderingRecord> { it.updatedAt }.thenBy { it.id })
        val page = rows.drop(offset).take(12)
        jsonObjectOf("ok" to true, "candidates" to JSONArray(candidates), "wonderings" to JSONArray(page.map { JSONObject(it.toJson()) }),
            "nextOffset" to if (offset + page.size < rows.size) offset + page.size else null)
    }
    register("save_wondering", "Save one tentative question per source, or resolve/dismiss it using a quoted user answer. Read before editing. Never turn a guess into a fact.",
        objSchema("sourceId" to str(), "observation" to str(), "question" to str(), "status" to oneOf("", "OPEN", "RESOLVED", "DISMISSED"),
            "expectedVersion" to num(), "sourceMessageId" to str(), "evidence" to str(),
            required = listOf("sourceId", "status"))) { a ->
        repo.learningTransaction {
            val sourceId = a.requiredString("sourceId")
            val id = "wondering:$sourceId"
            val old = repo.getWondering(id)
            if (repo.knowledgeForgotten(id) || repo.knowledgeForgotten(sourceId) || old?.deletedAt != null) throw ToolValidationException("Source or wondering was forgotten")
            if (old != null && (!a.has("expectedVersion") || old.version != a.getLong("expectedVersion"))) throw ToolValidationException("Read current wondering before editing")
            val status = a.requiredString("status")
            val data = old?.data() ?: JSONObject()
            if (status == "OPEN") {
                if (old != null && old.status != "OPEN") throw ToolValidationException("Do not reopen an asked or closed wondering")
                val source = repo.wonderingSource(sourceId) ?: throw ToolValidationException("Active source not found: use the ID of an open task, active grocery item or current memory. An emotional experience keeps its open question in save_emotional_thread's unresolved field")
                val observation = a.requiredString("observation")
                val question = a.requiredString("question")
                if (observation.length > 600 || question.length > 300) throw ToolValidationException("Keep the observation and question short")
                data.put("observation", observation).put("question", question).put("sourceSnapshot", source.take(800))
            } else {
                if (old == null) throw ToolValidationException("Wondering not found")
                val message = requireUserEvidence(a)
                data.put("sourceMessageId", message.id).put("answer", a.getString("evidence"))
            }
            val now = nowMillis()
            val row = WonderingRecord(id, sourceId, status, data.toString(), old?.createdAt ?: now, now, repo.deviceId, (old?.version ?: 0) + 1)
            repo.saveWondering(row)
            jsonObjectOf("ok" to true, "id" to id, "version" to row.version, "status" to status)
        }
    }
    register("ask_wondering", "Ask one saved question during a relevant chat. Delivers the question; do not repeat it in your reply. Never use during background runs.",
        objSchema("id" to str("Saved wondering ID (wondering:<sourceId>); its sourceId is also accepted."), "expectedVersion" to num(), required = listOf("id", "expectedVersion"))) { a ->
        if (runType != AgentRunType.CHAT) throw ToolValidationException("Wondering questions wait for conversation")
        val requestedId = a.requiredString("id")
        val canonicalId = if (requestedId.startsWith("wondering:")) requestedId else "wondering:$requestedId"
        val row = repo.getWondering(canonicalId)?.takeIf { it.deletedAt == null && it.status == "OPEN" }
            ?: throw ToolValidationException("No open wondering")
        if (row.version != a.getLong("expectedVersion")) throw ToolValidationException("Read current wondering")
        if (repo.wonderingSource(row.sourceId) == null) throw ToolValidationException("Source is no longer active")
        val now = nowMillis()
        if (repo.wonderings().any { it.data().optLong("askedAt", 0) > now - 3 * DAY }) throw ToolValidationException("Give them space; a question was asked recently")
        onUserMessage(row.data().getString("question"))
        repo.saveWondering(row.copy(status = "ASKED", content = row.data().put("askedAt", now).toString(), updatedAt = now, version = row.version + 1))
        jsonObjectOf("ok" to true, "id" to row.id, "delivered" to true)
    }
}
