package com.her.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import com.her.HerApplication
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
        due.forEach { reminder ->
            val now = repo.clock.nowMillis()
            if (!stillWanted(reminder)) {
                repo.saveReminder(reminder.copy(status = ReminderStatus.SKIPPED, updatedAt = now, version = reminder.version + 1))
                repo.logActivity("reminder", "Skipped, already done: ${reminder.message.take(80)}")
                return@forEach
            }
            // Saved before notifying, so opening the app from the notification shows the same words.
            val metadata = JSONObject().put("reminder", reminder.id).toString()
            repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, reminder.message, MessageStatus.SENT, metadata))
            notifier.showReminder(reminder.id, reminder.message)
            val next = nextFireAt(reminder, now)
            repo.saveReminder(
                reminder.copy(
                    status = if (next == null) ReminderStatus.FIRED else ReminderStatus.SCHEDULED,
                    fireAt = next ?: reminder.fireAt,
                    firedAt = now,
                    updatedAt = now,
                    version = reminder.version + 1,
                ),
            )
            repo.logActivity("reminder", "Fired: ${reminder.message.take(80)}")
        }
        return due
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
}

class AndroidReminderAlarms(private val context: Context) : ReminderAlarms {
    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    /** False on Android 12+ until they allow exact alarms; reminders then ring within a few minutes. */
    fun exactAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager?.canScheduleExactAlarms() == true

    override fun arm(reminder: Reminder) {
        val at = reminder.fireAt ?: return
        val manager = manager ?: return
        val intent = pendingIntent(reminder.id, create = true) ?: return
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
