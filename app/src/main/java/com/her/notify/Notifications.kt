package com.her.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.her.MainActivity
import com.her.R
import com.her.core.QuietHours
import com.her.data.secure.AppSettingsStore
import com.her.domain.CommitmentStatus
import com.her.domain.QueueStatus
import com.her.domain.TaskStatus
import java.time.ZonedDateTime

data class NotifyDecision(
    val notify: Boolean,
    val reason: String,
    val key: String,
)

class NotificationPolicy(private val settings: AppSettingsStore) {
    fun shouldNotify(
        key: String,
        urgent: Boolean,
        now: ZonedDateTime,
        cooldownMs: Long = 45 * 60 * 1000,
    ): NotifyDecision {
        val app = settings.read()
        val quiet = app.quietHours.contains(now.toLocalTime())
        if (quiet && !urgent) {
            return NotifyDecision(false, "quiet hours", key)
        }
        if (app.lastNotificationKey == key && now.toInstant().toEpochMilli() - app.lastNotificationAt < cooldownMs) {
            return NotifyDecision(false, "duplicate/cooldown", key)
        }
        if (!urgent && now.toInstant().toEpochMilli() - app.lastNotificationAt < 20 * 60 * 1000) {
            return NotifyDecision(false, "too soon after last notification", key)
        }
        return NotifyDecision(true, "allowed", key)
    }

    fun inQuietHours(now: ZonedDateTime, hours: QuietHours = settings.read().quietHours): Boolean =
        hours.contains(now.toLocalTime())
}

class Notifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_her), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    fun show(text: String, briefing: Boolean) {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DONE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_LATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (briefing) "Her" else "Her")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", done)
            .addAction(0, "Later", later)
            .addAction(0, "Open", open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL = "her"
        const val NOTIFICATION_ID = 17
        const val ACTION_DONE = "com.her.NOTIFY_DONE"
        const val ACTION_LATER = "com.her.NOTIFY_LATER"
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? com.her.HerApplication ?: return
        val graph = app.graph
        val pending = goAsync()
        graph.io.execute {
            try {
                when (intent.action) {
                    Notifier.ACTION_DONE -> {
                        kotlinx.coroutines.runBlocking {
                            graph.repo.commitments().firstOrNull { it.status == CommitmentStatus.OPEN }?.let {
                                graph.repo.saveCommitment(it.copy(status = CommitmentStatus.DONE, updatedAt = System.currentTimeMillis(), version = it.version + 1))
                                graph.repo.logActivity("notify", "Marked commitment done from notification: ${it.title}")
                            } ?: graph.repo.tasks().firstOrNull { it.status == TaskStatus.OPEN }?.let {
                                graph.repo.saveTask(it.copy(status = TaskStatus.DONE, updatedAt = System.currentTimeMillis(), version = it.version + 1))
                                graph.repo.logActivity("notify", "Marked task done from notification: ${it.title}")
                            }
                        }
                    }
                    Notifier.ACTION_LATER -> {
                        kotlinx.coroutines.runBlocking {
                            val now = System.currentTimeMillis()
                            graph.repo.saveAgentQueue(
                                com.her.domain.AgentQueueItem(
                                    id = com.her.core.newId(),
                                    description = "Follow up later on the last notification.",
                                    status = QueueStatus.OPEN,
                                    priority = 0.4,
                                    dueAt = now + 24 * 60 * 60 * 1000,
                                    relatedEntityType = null,
                                    relatedEntityId = null,
                                    createdAt = now,
                                    updatedAt = now,
                                    deviceId = graph.repo.deviceId,
                                    version = 1,
                                    deletedAt = null,
                                ),
                            )
                            graph.repo.logActivity("notify", "Snoozed notification until tomorrow.")
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
        NotificationManagerCompat.from(context).cancel(Notifier.NOTIFICATION_ID)
    }
}
