package com.her.companion

import com.her.data.repository.HerRepository
import com.her.domain.*
import com.her.core.jsonObjectOf
import org.json.JSONArray
import org.json.JSONObject

/** Read-only proposals; neither deadlines nor promises are changed. */
suspend fun capacityPlan(repo: HerRepository, minutes: Int, energy: String): JSONObject {
    val now = repo.clock.nowMillis()
    val until = now + minutes * 60_000L
    val events = repo.calendarInRange(now - 86_400_000L, until).filter { (it.endAt ?: it.startAt + 3_600_000L) > now }
    val freeUntil = events.minOfOrNull { maxOf(now, it.startAt) }?.coerceAtMost(until) ?: until
    var remaining = ((freeUntil - now) / 60_000).toInt().coerceAtLeast(0)
    val freeMinutes = remaining
    val capacities = repo.companionRecords().filter { it.kind == "CAPACITY" && !it.data().optBoolean("stale") }.associateBy { it.parentId }
    data class Action(val id: String, val title: String, val due: Long?, val updated: Long)
    val actions = repo.tasks().filter { it.status == TaskStatus.OPEN }.map { Action(it.id, it.title, it.dueAt, it.updatedAt) } +
        repo.commitments().filter { it.status in setOf(CommitmentStatus.OPEN, CommitmentStatus.MISSED) }.map { Action(it.id, it.title, it.dueAt, it.updatedAt) }
    val selected = JSONArray()
    val unknown = JSONArray()
    val energyOrder = listOf("LOW", "MEDIUM", "HIGH")
    for (action in actions.sortedWith(compareBy<Action> { it.due ?: Long.MAX_VALUE }.thenByDescending { capacities[it.id]?.data()?.optInt("priority", 0) ?: 0 }.thenBy { it.id })) {
        val data = capacities[action.id]?.data()
        if (data == null || !data.has("minutes")) {
            if (unknown.length() < 3) unknown.put(jsonObjectOf("id" to action.id, "title" to action.title.take(160), "missing" to "conservative duration estimate"))
            continue
        }
        val dependencies = data.optJSONArray("dependencies") ?: JSONArray()
        var blocked = false
        for (i in 0 until dependencies.length()) {
            val id = dependencies.getString(i)
            if (repo.getTask(id)?.let { it.deletedAt == null && it.status == TaskStatus.DONE } != true &&
                repo.getCommitment(id)?.let { it.deletedAt == null && it.status == CommitmentStatus.DONE } != true) blocked = true
        }
        if (blocked || data.getInt("minutes") > remaining) continue
        if (energy.isNotEmpty() && data.optString("energy").isNotEmpty() && energyOrder.indexOf(data.optString("energy")) > energyOrder.indexOf(energy)) continue
        if (selected.length() == 3) break
        val duration = data.getInt("minutes")
        selected.put(jsonObjectOf("id" to action.id, "title" to action.title.take(160), "minutes" to duration, "dueAt" to action.due,
            "updatedAt" to action.updated, "reason" to "Fits remaining uninterrupted time; prerequisites complete" + if (action.due != null) "; deadline considered" else "; priority considered"))
        remaining -= duration
    }
    return jsonObjectOf("ok" to true, "status" to "SUGGESTED", "actions" to selected, "unknownEstimates" to unknown,
        "availableMinutes" to freeMinutes, "validUntil" to freeUntil, "generatedAt" to now,
        "calendarCoverage" to "Locally known events only; refresh get_calendar_events before planning. Missing end times reserve one hour.",
        "externalActionPerformed" to false)
}
