package com.her.agent.tools

import com.her.core.*
import com.her.data.db.CompanionRecord
import com.her.companion.*
import com.her.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal val supportToolNames = setOf("suggest_daily_plan", "accept_daily_plan", "manage_occurrence", "get_situation_briefing", "get_executive_briefing")

internal fun ToolRegistry.registerSupportTools() {
    register("get_executive_briefing", "List decisions, blockers, dependency risks, and overdue delegated outcomes.",
        objSchema("offset" to num("Nonnegative integer"))) { a ->
        val offset = a.optInt("offset", 0)
        if (offset < 0 || (a.has("offset") && a.getDouble("offset") != offset.toDouble())) throw ToolValidationException("Invalid offset")
        executiveBriefing(repo, offset)
    }
    register("suggest_daily_plan", "Suggest at most three actions that fit their stated time and energy. Unknown durations stay unknown.",
        objSchema("minutes" to num("1..480"), "energy" to oneOf("User-stated only", "LOW", "MEDIUM", "HIGH"), required = listOf("minutes"))) { a ->
        val minutes = a.getInt("minutes")
        if (minutes !in 1..480) throw ToolValidationException("minutes must be 1..480")
        capacityPlan(repo, minutes, a.optString("energy"))
    }
    register("accept_daily_plan", "Record only the actions they explicitly accept from the current suggestion.",
        objSchema("id" to str(), "minutes" to num(), "energy" to oneOf("", "LOW", "MEDIUM", "HIGH"), "actionIds" to arr(), "sourceMessageId" to str(), "evidence" to str(),
            required = listOf("id", "minutes", "actionIds", "sourceMessageId", "evidence"))) { a ->
        repo.learningTransaction {
            requireUserEvidence(a)
            val id = "PLAN:${a.requiredString("id").removePrefix("PLAN:")}"
            if (id.length > 180) throw ToolValidationException("ID too long")
            val ids = a.getJSONArray("actionIds")
            if (ids.length() !in 1..3) throw ToolValidationException("Accept one to three actions")
            val previous = repo.getCompanionRecord(id)
            if (previous != null) {
                if (previous.deletedAt != null || previous.data().optString("sourceMessageId") != a.getString("sourceMessageId") || previous.data().optJSONArray("relatedIds")?.toString() != ids.toString()) throw ToolValidationException("Use a new plan ID")
                return@learningTransaction jsonObjectOf("ok" to true, "id" to id, "created" to false)
            }
            val minutes = a.getInt("minutes")
            if (minutes !in 1..480) throw ToolValidationException("minutes must be 1..480")
            val proposal = capacityPlan(repo, minutes, a.optString("energy"))
            val actions = proposal.getJSONArray("actions")
            val eligible = (0 until actions.length()).map { actions.getJSONObject(it).getString("id") }.toSet()
            if ((0 until ids.length()).any { ids.getString(it) !in eligible } || (0 until ids.length()).map { ids.getString(it) }.toSet().size != ids.length()) throw ToolValidationException("Capacity changed or actions do not fit; suggest again")
            val now = nowMillis()
            val data = jsonObjectOf("status" to "ACCEPTED", "relatedIds" to ids, "validUntil" to proposal.getLong("validUntil"), "sourceMessageId" to a.getString("sourceMessageId"), "evidence" to a.getString("evidence"))
            repo.saveCompanionRecord(CompanionRecord(id, id, "PLAN", data.toString(), now, now, repo.deviceId, 1))
            jsonObjectOf("ok" to true, "id" to id, "created" to true, "externalActionPerformed" to false)
        }
    }
    register("manage_occurrence", "Complete, skip, or postpone one occurrence of an existing schedule.",
        objSchema("id" to str("Schedule id from save_responsibility_schedule"), "scheduledAt" to num("Epoch of this occurrence, not a later one"), "action" to oneOf("", "SKIPPED", "COMPLETED", "POSTPONED"), "until" to num(), "sourceMessageId" to str(), "evidence" to str(),
            required = listOf("id", "scheduledAt", "action", "sourceMessageId", "evidence"))) { a ->
        repo.learningTransaction {
            requireUserEvidence(a)
            // The responsibility or routine ID works too: each has at most one schedule.
            val schedule = (repo.getCompanionRecord(companionRecordId("SCHEDULE", a.requiredString("id"))) ?: repo.companionRecords().firstOrNull { it.kind == "SCHEDULE" && it.parentId == a.getString("id") })
                ?.takeIf { it.kind == "SCHEDULE" && it.deletedAt == null }
                ?: throw ToolValidationException("Retrieve the schedule first")
            fun millis(raw: Long) = if (raw in 1_000_000_000L..9_999_999_999L) raw * 1000 else raw
            val at = millis(a.getLong("scheduledAt"))
            // Local occurrence index survives a profile timezone change; retain its original identity.
            val zone = repo.profileZone()
            val old = findOccurrence(repo, schedule, at)
            val index = occurrenceIndex(repo, schedule, at) ?: old?.data()?.optLong("occurrenceIndex", -1)?.takeIf { it >= 0 }
                ?: throw ToolValidationException("Not a known occurrence; clarify which date")
            val id = old?.id ?: occurrenceId(schedule, at)
            val action = a.requiredString("action")
            val until = millis(a.optLong("until"))
            if (old?.data()?.optString("status") == action && (action != "POSTPONED" || old.data().optLong("until") == until)) return@learningTransaction jsonObjectOf("ok" to true, "id" to id, "created" to false)
            if (old?.data()?.optString("status") == "COMPLETED") throw ToolValidationException("Occurrence already completed")
            if (action == "POSTPONED" && (until <= nowMillis() || until >= scheduleOccurrence(schedule, index + 1, zone))) throw ToolValidationException("Postpone to a future time before the next occurrence")
            val now = nowMillis()
            val data = jsonObjectOf("status" to action, "scheduledAt" to at, "occurrenceIndex" to index, "scheduleIdentity" to scheduleIdentity(schedule), "until" to if (action == "POSTPONED") until else null,
                "sourceMessageId" to a.getString("sourceMessageId"), "evidence" to a.getString("evidence"))
            repo.saveCompanionRecord(CompanionRecord(id, schedule.id, "OCCURRENCE", data.toString(), old?.createdAt ?: now, now, repo.deviceId, (old?.version ?: 0) + 1))
            val reminder = repo.getReminder(schedule.data().optString("reminderId"))
            // A late report must never consume a later scheduled reminder.
            if (reminder != null && reminder.deletedAt == null && reminder.status != ReminderStatus.CANCELLED && (reminder.fireAt == at || reminder.fireAt == old?.data()?.optLong("until"))) {
                val next = if (action == "POSTPONED") until else nextScheduleAt(repo, schedule, maxOf(now, at))
                val updated = reminder.copy(fireAt = next ?: reminder.fireAt, status = if (next == null) ReminderStatus.SKIPPED else ReminderStatus.SCHEDULED, updatedAt = now, version = reminder.version + 1)
                repo.saveReminder(updated)
                if (updated.deviceId == repo.deviceId) { if (next == null) reminderAlarms.disarm(updated.id) else reminderAlarms.arm(updated) }
            }
            if (action == "COMPLETED") repo.getResponsibility(schedule.parentId)?.let { repo.saveResponsibility(it.copy(lastCompletedAt = maxOf(it.lastCompletedAt ?: 0, at), nextDueAt = nextScheduleAt(repo, schedule, now), updatedAt = now, version = it.version + 1)) }
            jsonObjectOf("ok" to true, "id" to id, "created" to (old == null), "externalActionPerformed" to false)
        }
    }
    register("get_situation_briefing", "Catch them up on what changed, including after time away. The reply must say external changes may be missing and that no inbox or trip monitoring occurred.",
        objSchema("query" to str(), "personId" to str(), "since" to num("Epoch millis; the window must contain now"), "until" to num("Epoch millis; within 400 days of since"))) { a ->
        val now = nowMillis()
        // Providers fill unused numbers with 0 (unspecified), and an until read from the clock a moment ago is already past; neither should fail.
        val since = a.optLong("since").takeIf { it > 0 }?.let { minOf(it, now) } ?: (now - 7 * 86_400_000L)
        val until = a.optLong("until").takeIf { it > 0 }?.let { maxOf(it, now) } ?: (now + 7 * 86_400_000L)
        if (until - since > 400L * 86_400_000L) throw ToolValidationException("Choose a window within 400 days containing now")
        val person = a.optStringOrNull("personId")
        if (person != null && repo.getPerson(person)?.deletedAt != null || person != null && repo.getPerson(person) == null) throw ToolValidationException("Person not found")
        val query = a.optString("query").trim()
        fun relevant(text: String) = query.isBlank() || query.split(Regex("\\s+")).any { text.contains(it, true) }
        val items = mutableListOf<JSONObject>()
        fun add(id: String, kind: String, text: String, updated: Long, due: Long? = null, stale: Boolean = false, basis: String = "STORED") {
            items += jsonObjectOf("id" to id, "kind" to kind, "summary" to text.take(420), "updatedAt" to updated, "changedSince" to (updated >= since),
                "dueAt" to due, "stale" to (stale || now - updated > 30L * 86_400_000L), "basis" to basis)
        }
        if (person == null) {
            repo.tasks().filter { it.status == TaskStatus.OPEN && relevant(it.title) && (it.updatedAt >= since || (it.dueAt != null && it.dueAt <= until)) }.forEach { add(it.id, "TASK", it.title, it.updatedAt, it.dueAt) }
            repo.calendarInRange(now, until).filter { relevant(it.title) }.forEach { add(it.id, "EVENT", it.title, it.updatedAt, it.startAt) }
        }
        repo.commitments().filter { it.status in setOf(CommitmentStatus.OPEN, CommitmentStatus.MISSED) && (person?.let { p -> it.promisedTo == p || it.promisedTo == repo.getPerson(p)?.name } ?: relevant(it.title)) }.forEach { add(it.id, "COMMITMENT", it.title, it.updatedAt, it.dueAt) }
        repo.companionRecords().filter { it.kind in setOf("WAITING", "THREAD", "DECISION") && !it.data().optBoolean("sensitive") }.forEach { row ->
            val data = row.data()
            val links = data.optJSONArray("relatedIds") ?: JSONArray()
            val related = if (person != null) data.optString("personId") == person || (0 until links.length()).any { links.optString(it) == person } else relevant(data.optString("summary") + data.optString("title") + data.optString("expectedOutcome"))
            if (related && data.optString("status") !in setOf("CLOSED", "CANCELLED", "RESOLVED") && (person != null || row.updatedAt >= since || row.kind == "WAITING")) {
                add(row.id, row.kind, data.optString("summary", data.optString("expectedOutcome")), row.updatedAt,
                    data.optLong("deadline").takeIf { it > 0 }, data.optBoolean("stale"), data.optString("basis", "UNKNOWN") )
            }
        }
        val sorted = items.sortedWith(compareBy<JSONObject> { if (!it.isNull("dueAt") && it.getLong("dueAt") <= until) 0 else 1 }.thenByDescending { it.getLong("updatedAt") })
        jsonObjectOf("ok" to true, "items" to JSONArray(sorted.take(8)), "omitted" to maxOf(0, items.size - 8), "asOf" to now,
            "coverage" to "Stored records and local calendar mirror only; external changes may be missing. No inbox or trip monitoring occurred.", "automaticScheduleCreated" to false)
    }
}
