package com.her.companion

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.data.repository.HerRepository
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.dependencyIds(): List<String> = optJSONArray("dependencies")?.let { a ->
    (0 until a.length()).map { a.getString(it) }
} ?: emptyList()

/** Recover the original contiguous passage only when every quoted sentence occurs verbatim, in order. */
fun delegationEvidence(source: String, quote: String): String {
    if (source.contains(quote)) return quote
    val parts = quote.split(Regex("(?<=[.!?؟;؛])\\s+")).filter { it.isNotBlank() }
    if (parts.size < 2) throw ToolValidationException("Evidence must quote the source message")
    var start = -1
    var end = 0
    for (part in parts) {
        if (!part.any { it.isLetterOrDigit() }) throw ToolValidationException("Evidence must quote the source message")
        val at = source.indexOf(part, end)
        if (at < 0) throw ToolValidationException("Evidence must quote the source message")
        if (start < 0) start = at
        end = at + part.length
    }
    return source.substring(start, end)
}

/** Extend the existing waiting record; evidence and optimistic locking are owned by its writer. */
suspend fun validateDelegation(repo: HerRepository, id: String, data: JSONObject, changes: JSONObject, source: String) {
    val previous = repo.getCompanionRecord(id)?.takeIf { it.deletedAt == null }?.data()
    for (key in listOf("progress", "blocker", "decisionNeeded")) {
        if (changes.has(key) && !changes.isNull(key) && changes.opt(key) != previous?.opt(key)) {
            val claim = changes.getString(key)
            if (claim.isNotBlank() && (!claim.any { it.isLetterOrDigit() } || !source.contains(claim))) throw ToolValidationException("$key must quote the reported update; use an empty string to clear or when unknown")
        }
    }
    if (data.optBoolean("delegated") && data.optString("basis") != "EXPLICIT")
        throw ToolValidationException("Delegation requires explicit user evidence")
    val rows = repo.companionRecords().filter { it.kind == "WAITING" }.associateBy { it.id }
    val deps = data.dependencyIds().map { raw ->
        when {
            raw in rows -> raw
            "WAITING:$raw" in rows -> "WAITING:$raw"
            else -> rows.values.singleOrNull { it.parentId == raw }?.id ?: raw
        }
    }
    if (data.has("dependencies")) data.put("dependencies", JSONArray(deps))
    fun canonical(raw: String): String = when {
        raw == data.optString("openLoopId") -> id
        raw in rows -> raw
        "WAITING:$raw" in rows -> "WAITING:$raw"
        else -> rows.values.singleOrNull { it.parentId == raw }?.id ?: raw
    }
    if (deps.distinct().size != deps.size) throw ToolValidationException("Duplicate dependency")
    for (dep in deps) {
        if (canonical(dep) == id || (dep !in rows && repo.getOpenLoop(dep)?.let { it.deletedAt == null } != true))
            throw ToolValidationException("Dependency must be an existing different waiting record or open loop")
        val visited = mutableSetOf<String>()
        fun reachesSelf(raw: String): Boolean {
            val current = canonical(raw)
            if (current == id) return true
            if (!visited.add(current)) return false
            return rows[current]?.data()?.dependencyIds()?.any { reachesSelf(it) } == true
        }
        if (reachesSelf(dep)) throw ToolValidationException("Dependencies must not form a cycle")
    }
}

/** Deterministic exceptions, never a prediction of success/failure from missing reports. */
suspend fun executiveBriefing(repo: HerRepository, offset: Int): JSONObject {
    val now = repo.clock.nowMillis()
    val rows = repo.companionRecords().filter { it.kind == "WAITING" }.associateBy { it.id }
    val items = mutableListOf<JSONObject>()
    var active = 0
    for (row in rows.values) {
        val d = row.data()
        if (!d.optBoolean("delegated") || d.optString("status") != "OPEN") continue
        val loop = repo.getOpenLoop(row.parentId)
        if (loop == null || loop.deletedAt != null || loop.status.name != "OPEN") continue
        active++
        val reasons = mutableListOf<String>()
        fun present(key: String) = !d.isNull(key) && d.optString(key).isNotBlank()
        if (present("decisionNeeded")) reasons += "DECISION_NEEDED"
        if (present("blocker")) reasons += "REPORTED_BLOCKER"
        val dependencies = d.dependencyIds().map { id ->
            val dependency = rows[id] ?: rows.values.singleOrNull { it.parentId == id }
            val dep = dependency?.data()
            val state = when {
                dep == null -> "UNKNOWN"
                dep.optString("status") == "RESOLVED" -> "RESOLVED"
                dep.optString("status") == "CANCELLED" -> "CANCELLED"
                !dep.isNull("blocker") && dep.optString("blocker").isNotBlank() -> "REPORTED_BLOCKER"
                dep.optLong("deadline") in 1 until now -> "OVERDUE"
                else -> "OPEN"
            }
            jsonObjectOf("id" to id, "state" to state)
        }
        if (dependencies.any { it.getString("state") in setOf("UNKNOWN", "CANCELLED", "REPORTED_BLOCKER", "OVERDUE") }) reasons += "DEPENDENCY_RISK"
        val deadline = d.optLong("deadline").takeIf { it > 0 }
        if (deadline != null && deadline < now) reasons += "OVERDUE"
        else if (deadline != null && deadline <= now + 86_400_000L) reasons += "DUE_WITHIN_24H"
        val sourceKey = if (present("progress")) d.optJSONObject("fieldSources")?.optString("progress") else null
        val progressAt = sourceKey?.let { d.optJSONObject("sourceQuotes")?.optJSONObject(it)?.optLong("sourceDate") }?.takeIf { it > 0 }
        if (progressAt == null) reasons += "NO_REPORTED_UPDATE"
        else if (now - progressAt > 7L * 86_400_000L) reasons += "UPDATE_OLDER_THAN_7_DAYS"
        if (d.optBoolean("stale")) reasons += "CONTEXT_NEEDS_REFRESH"
        if (reasons.isEmpty()) continue
        val owner = repo.getPerson(d.optString("personId"))?.takeIf { it.deletedAt == null }
        items += jsonObjectOf("id" to row.id, "owner" to owner?.name, "personId" to owner?.id,
            "expectedOutcome" to d.optString("expectedOutcome").take(240), "deadline" to deadline,
            "deadlineLocal" to deadline?.let { java.time.Instant.ofEpochMilli(it).atZone(repo.profileZone()).toString() },
            "priority" to if (d.has("priority")) d.getInt("priority") else null,
            "reasons" to JSONArray(reasons), "dependencies" to JSONArray(dependencies),
            "progress" to if (present("progress")) d.getString("progress").take(240) else null,
            "progressReportedAt" to progressAt, "blocker" to if (present("blocker")) d.getString("blocker").take(240) else null,
            "decisionNeeded" to if (present("decisionNeeded")) d.getString("decisionNeeded").take(240) else null,
            "updatedAt" to row.updatedAt, "sourceMessageId" to d.optString("sourceMessageId"),
            "rank" to when { reasons.any { it in setOf("DECISION_NEEDED", "REPORTED_BLOCKER", "DEPENDENCY_RISK") } -> 0; "OVERDUE" in reasons -> 1; "DUE_WITHIN_24H" in reasons -> 2; else -> 3 })
    }
    val sorted = items.sortedWith(compareBy<JSONObject> { it.getInt("rank") }
        .thenByDescending { it.optInt("priority", -1) }.thenBy { if (it.isNull("deadline")) Long.MAX_VALUE else it.getLong("deadline") }.thenBy { it.getString("id") })
    val page = mutableListOf<JSONObject>()
    var size = 0
    for (item in sorted.drop(offset)) {
        if (page.size >= 5 || (page.isNotEmpty() && size + item.toString().length > 5000)) break
        page += item; size += item.toString().length
    }
    return jsonObjectOf("ok" to true, "items" to JSONArray(page), "activeDelegations" to active, "totalExceptions" to items.size,
        "nextOffset" to if (offset + page.size < items.size) offset + page.size else null,
        "omitted" to (items.size - offset - page.size).coerceAtLeast(0), "asOf" to now,
        "coverage" to "Saved user reports only; no inbox or project-system monitoring. Missing updates are unknown, overdue is not failed. Dependency risk is not a predicted missed deadline.",
        "automaticScheduleCreated" to false)
}
