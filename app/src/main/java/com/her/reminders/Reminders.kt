package com.her.reminders

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import com.her.HerApplication
import com.her.MainActivity
import com.her.core.newId
import com.her.data.repository.HerRepository
import com.her.domain.CommitmentStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.Reminder
import com.her.domain.ReminderRepeat
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import com.her.domain.TaskStatus
import com.her.notify.Notifier
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Arms and disarms the wake-up for a clock reminder. */
interface ReminderAlarms {
    fun arm(reminder: Reminder)
    fun disarm(id: String)

    /** Whether a reminder or alarm rings on the minute; Android 12+ asks the user first. */
    fun exactAllowed(): Boolean = true

    companion object {
        val NONE: ReminderAlarms = object : ReminderAlarms {
            override fun arm(reminder: Reminder) = Unit
            override fun disarm(id: String) = Unit
        }
    }
}

/** Arms every clock reminder this device owns and disarms the rest. Safe to call any time. */
suspend fun ReminderAlarms.rearmAll(repo: HerRepository) {
    repo.reminders()
        .filter { it.trigger == ReminderTrigger.TIME && it.deviceId == repo.deviceId }
        .forEach { if (it.status == ReminderStatus.SCHEDULED && it.fireAt != null) arm(it) else disarm(it.id) }
}

/** Sets an alarm in the phone's own clock app. */
interface AlarmClockPort {
    /** [days] are java.util.Calendar weekdays (Sunday = 1); empty means only the next occurrence. */
    fun set(hour: Int, minute: Int, label: String?, days: List<Int>): Result<Unit>

    companion object {
        val UNAVAILABLE: AlarmClockPort = object : AlarmClockPort {
            override fun set(hour: Int, minute: Int, label: String?, days: List<Int>): Result<Unit> =
                Result.failure(IllegalStateException("Setting clock alarms is not available here"))
        }
    }
}

/**
 * Delivers clock reminders that are due. The alarm receiver, the hourly worker, and the scenario harness all
 * call it, so a reminder missed while the phone was off still fires the next time any of them runs.
 */
class ReminderDelivery(
    private val repo: HerRepository,
    private val notifier: Notifier,
) {
    suspend fun fireDue(): List<Reminder> {
        val due = repo.dueTimeReminders()
        due.forEach { candidate ->
            val deliver = repo.learningTransaction {
                val reminder = repo.getReminder(candidate.id)?.takeIf { it.deletedAt == null && it.status == ReminderStatus.SCHEDULED && it.fireAt == candidate.fireAt }
                    ?: return@learningTransaction null
                val now = repo.clock.nowMillis()
                val schedule = com.her.companion.scheduleForReminder(repo, reminder)
                val postponed = schedule?.let { s -> repo.companionRecords().firstOrNull { it.kind == "OCCURRENCE" && it.parentId == s.id && it.data().optString("status") == "POSTPONED" && it.data().optLong("until") == reminder.fireAt } }
                val originalAt = postponed?.data()?.optLong("scheduledAt") ?: reminder.fireAt!!
                val occurrenceId = schedule?.let { com.her.companion.occurrenceId(it, originalAt) }
                val occurrence = schedule?.let { com.her.companion.findOccurrence(repo, it, originalAt) }
                val data = schedule?.data()
                val paused = data != null && ((data.optString("status") == "PAUSED" && data.optLong("pauseUntil") <= 0) || data.optLong("pauseUntil") > now)
                val alreadyHandled = occurrence?.data()?.optString("status") in setOf("SKIPPED", "COMPLETED", "NOTIFIED")
                val deferred = occurrence?.data()?.optString("status") == "POSTPONED" && occurrence.data().optLong("until") > now
                val wanted = stillWanted(reminder)
                val next = if (deferred) occurrence!!.data().getLong("until") else if (schedule != null) com.her.companion.nextScheduleAt(repo, schedule, now) else nextFireAt(reminder, now)
                val key = "reminder-delivery:${reminder.id}:${reminder.fireAt}"
                val reserved = repo.getCompanionRecord(key) != null
                repo.saveReminder(reminder.copy(status = when {
                    !wanted -> ReminderStatus.SKIPPED
                    next != null -> ReminderStatus.SCHEDULED
                    paused -> ReminderStatus.SKIPPED
                    else -> ReminderStatus.FIRED
                }, fireAt = next ?: reminder.fireAt, firedAt = if (!paused && !alreadyHandled && !deferred && wanted) now else reminder.firedAt,
                    updatedAt = now, version = reminder.version + 1))
                if (paused || alreadyHandled || deferred || !wanted || reserved) return@learningTransaction null
                repo.saveCompanionRecord(com.her.data.db.CompanionRecord(key, reminder.id, "DELIVERY", "{}", now, now, repo.deviceId, 1))
                if (occurrenceId != null) {
                    val occurrenceData = (occurrence?.data() ?: JSONObject()).put("status", "NOTIFIED").put("scheduledAt", originalAt)
                        .put("occurrenceIndex", com.her.companion.occurrenceIndex(repo, schedule, originalAt)).put("scheduleIdentity", com.her.companion.scheduleIdentity(schedule))
                    repo.saveCompanionRecord(com.her.data.db.CompanionRecord(occurrence?.id ?: occurrenceId, schedule.id, "OCCURRENCE", occurrenceData.toString(), occurrence?.createdAt ?: now, now, repo.deviceId, (occurrence?.version ?: 0) + 1))
                }
                reminder
            }
            if (deliver != null) {
                val metadata = JSONObject().put("reminder", deliver.id).toString()
                repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, deliver.message, MessageStatus.SENT, metadata))
                if (deliver.alarm) notifier.showAlarm(deliver.id, deliver.message) else notifier.showReminder(deliver.id, deliver.message)
                repo.logActivity("reminder", "Fired: ${deliver.message.take(80)}")
            }
        }
        return due
    }

    /** "Done" on a reminder that rang: closes the task or commitment it was waiting on, without the model. */
    suspend fun done(id: String) {
        val reminder = repo.getReminder(id) ?: return
        val entityId = reminder.onlyIfEntityId
        val now = repo.clock.nowMillis()
        when (reminder.onlyIfEntityType) {
            "tasks" -> entityId?.let { repo.getTask(it) }?.takeIf { it.status == TaskStatus.OPEN }?.let {
                repo.saveTask(it.copy(status = TaskStatus.DONE, updatedAt = now, version = it.version + 1))
            }
            "commitments" -> entityId?.let { repo.getCommitment(it) }
                ?.takeIf { it.status == CommitmentStatus.OPEN || it.status == CommitmentStatus.MISSED }
                ?.let { repo.saveCommitment(it.copy(status = CommitmentStatus.DONE, updatedAt = now, version = it.version + 1)) }
        }
        repo.logActivity("reminder", "Done from the notification: ${reminder.message.take(80)}")
    }

    /** Rings the same words again in [minutes] as a one-off, keeping any "only if still open" condition. */
    suspend fun snooze(id: String, minutes: Long = SNOOZE_MINUTES): Reminder? {
        val reminder = repo.getReminder(id) ?: return null
        val now = repo.clock.nowMillis()
        val again = reminder.copy(
            id = newId(),
            trigger = ReminderTrigger.TIME,
            fireAt = now + minutes * 60 * 1000,
            repeat = ReminderRepeat.NONE,
            status = ReminderStatus.SCHEDULED,
            firedAt = null,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
        )
        repo.saveReminder(again)
        repo.logActivity("reminder", "Snoozed $minutes minutes: ${reminder.message.take(80)}")
        return again
    }

    /** An "only if I haven't done it" reminder is dropped once its task or commitment is finished. */
    private suspend fun stillWanted(reminder: Reminder): Boolean {
        val id = reminder.onlyIfEntityId ?: return true
        return when (reminder.onlyIfEntityType) {
            "tasks" -> repo.getTask(id)?.let { it.deletedAt == null && it.status == TaskStatus.OPEN } ?: false
            "commitments" -> repo.getCommitment(id)?.let {
                it.deletedAt == null && (it.status == CommitmentStatus.OPEN || it.status == CommitmentStatus.MISSED)
            } ?: false
            else -> true
        }
    }

    private suspend fun nextFireAt(reminder: Reminder, now: Long): Long? {
        val start = reminder.fireAt ?: return null
        if (reminder.repeat == ReminderRepeat.NONE) return null
        var next = Instant.ofEpochMilli(start).atZone(repo.profileZone())
        while (next.toInstant().toEpochMilli() <= now) {
            next = when (reminder.repeat) {
                ReminderRepeat.DAILY -> next.plusDays(1)
                ReminderRepeat.WEEKLY -> next.plusWeeks(1)
                ReminderRepeat.MONTHLY -> next.plusMonths(1)
                ReminderRepeat.NONE -> return null
            }
        }
        return next.toInstant().toEpochMilli()
    }

    companion object {
        const val SNOOZE_MINUTES = 10L
    }
}

class AndroidReminderAlarms(private val context: Context) : ReminderAlarms {
    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    /** False on Android 12+ until they allow exact alarms; reminders then ring within a few minutes. */
    override fun exactAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager?.canScheduleExactAlarms() == true

    override fun arm(reminder: Reminder) {
        val at = reminder.fireAt ?: return
        val manager = manager ?: return
        val intent = pendingIntent(reminder.id, create = true) ?: return
        // An alarm clock alarm shows in the status bar and is let through Doze on the minute.
        if (reminder.alarm && exactAllowed()) {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (runCatching { manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, open), intent) }.isSuccess) return
        }
        val exact = exactAllowed() &&
            runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }.isSuccess
        if (!exact) manager.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW_MS, intent)
    }

    override fun disarm(id: String) {
        val intent = pendingIntent(id, create = false) ?: return
        manager?.cancel(intent)
        intent.cancel()
    }

    private fun pendingIntent(id: String, create: Boolean): PendingIntent? {
        // The data URI gives each reminder its own PendingIntent without leaning on request-code hashes.
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("her-reminder://$id"))
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    companion object {
        const val INEXACT_WINDOW_MS = 10 * 60 * 1000L
    }
}

class SystemAlarmClock(private val context: Context) : AlarmClockPort {
    override fun set(hour: Int, minute: Int, label: String?, days: List<Int>): Result<Unit> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (intent.resolveActivity(context.packageManager) == null) {
            error("No clock app on this phone accepts alarms")
        }
        // Android drops activity starts from the background without an error, so an alarm set from a notification
        // reply or a background pass would never exist. Say so rather than report success.
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        if (state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            error("The clock app can only be opened while Her is on screen. Set a reminder for that time instead.")
        }
        context.startActivity(intent)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val graph = (context.applicationContext as? HerApplication)?.graph ?: return
        val pending = goAsync()
        graph.io.execute {
            try {
                runBlocking {
                    graph.reminderDelivery.fireDue()
                    graph.reminderAlarms.rearmAll(graph.repo)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.her.REMINDER_FIRE"
    }
}
