package com.her.companion

import com.her.data.db.CompanionRecord
import com.her.data.repository.HerRepository
import com.her.domain.Reminder
import com.her.domain.ReminderStatus
import com.her.reminders.ReminderAlarms
import java.time.LocalDateTime
import java.time.ZoneId

/** Recompute from the local anchor, avoiding permanent drift after DST gaps or month-end clamping. */
fun scheduleOccurrence(row: CompanionRecord, index: Long, profileZone: ZoneId): Long {
    require(index >= 0 && index <= 1_000_000) { "Invalid occurrence index" }
    val data = row.data()
    val anchor = LocalDateTime.parse(data.getString("anchorLocal"))
    val steps = index * data.optInt("interval", 1).coerceIn(1, 52)
    val local = when (data.getString("repeat")) {
        "DAILY" -> anchor.plusDays(steps)
        "WEEKLY" -> anchor.plusWeeks(steps)
        "MONTHLY" -> anchor.plusMonths(steps)
        else -> error("Unsupported recurrence")
    }
    val zone = if (data.optString("zonePolicy") == "PROFILE") profileZone else ZoneId.of(data.getString("zone"))
    // java.time shifts gaps forward and chooses the earlier offset for overlaps, as existing scheduling does.
    return local.atZone(zone).toInstant().toEpochMilli()
}

fun occurrenceId(schedule: CompanionRecord, at: Long) = "occurrence:${schedule.id}:$at"

fun scheduleIdentity(row: CompanionRecord): String = row.data().let { "${it.optString("anchorLocal")}:${it.optString("repeat")}:${it.optInt("interval", 1)}" }

suspend fun occurrenceIndex(repo: HerRepository, row: CompanionRecord, at: Long): Long? {
    val zone = repo.profileZone()
    var lo = 0L; var hi = 1_000_000L
    while (lo < hi) { val mid = (lo + hi) / 2; if (scheduleOccurrence(row, mid, zone) < at) lo = mid + 1 else hi = mid }
    return lo.takeIf { scheduleOccurrence(row, it, zone) == at }
}

suspend fun findOccurrence(repo: HerRepository, row: CompanionRecord, at: Long): CompanionRecord? {
    repo.getCompanionRecord(occurrenceId(row, at))?.let { return it }
    val index = occurrenceIndex(repo, row, at) ?: return null
    return repo.companionRecords().firstOrNull { it.kind == "OCCURRENCE" && it.parentId == row.id && it.data().optString("scheduleIdentity") == scheduleIdentity(row) && it.data().optLong("occurrenceIndex", -1) == index }
}

suspend fun nextScheduleAt(repo: HerRepository, row: CompanionRecord, after: Long): Long? {
    val data = row.data()
    val pause = data.optLong("pauseUntil", 0)
    if (data.optString("status") == "PAUSED" && pause <= 0) return null
    val threshold = maxOf(after, pause - 1)
    val zone = repo.profileZone()
    var low = 0L
    var high = 1_000_000L
    while (low < high) {
        val mid = (low + high) / 2
        if (scheduleOccurrence(row, mid, zone) <= threshold) low = mid + 1 else high = mid
    }
    val postponed = repo.companionRecords().filter { it.kind == "OCCURRENCE" && it.parentId == row.id && it.data().optString("status") == "POSTPONED" }
        .map { it.data().optLong("until") }.filter { it > threshold }.minOrNull()
    repeat(100) {
        val at = scheduleOccurrence(row, low++, zone)
        val occurrence = findOccurrence(repo, row, at)?.data()
        if (occurrence == null || occurrence.optString("status") !in setOf("SKIPPED", "COMPLETED", "NOTIFIED", "POSTPONED")) return minOf(at, postponed ?: Long.MAX_VALUE)
    }
    return postponed
}

suspend fun refreshScheduleReminder(repo: HerRepository, row: CompanionRecord, alarms: ReminderAlarms) {
    val reminder = repo.getReminder(row.data().optString("reminderId"))?.takeIf { it.deletedAt == null && it.status != ReminderStatus.CANCELLED } ?: return
    val next = nextScheduleAt(repo, row, repo.clock.nowMillis() - 1)
    val updated = reminder.copy(fireAt = next ?: reminder.fireAt, status = if (next == null) ReminderStatus.SKIPPED else ReminderStatus.SCHEDULED,
        updatedAt = repo.clock.nowMillis(), version = reminder.version + 1)
    repo.saveReminder(updated)
    if (updated.deviceId == repo.deviceId) { if (next == null) alarms.disarm(updated.id) else alarms.arm(updated) }
}

suspend fun scheduleForReminder(repo: HerRepository, reminder: Reminder): CompanionRecord? =
    repo.companionRecords().firstOrNull { it.kind == "SCHEDULE" && it.data().optString("reminderId") == reminder.id }
