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
import androidx.core.app.RemoteInput
import com.her.MainActivity
import com.her.R
import com.her.core.QuietHours
import com.her.data.secure.AppSettingsStore
import com.her.domain.QueueStatus
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

open class Notifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_her), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL, context.getString(R.string.channel_her_reminders), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    /** A reminder they asked for rings even in quiet hours, and each one keeps its own notification. */
    open fun showReminder(id: String, text: String) {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Her")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_BASE + (id.hashCode() and 0xFFFF), notification)
        } catch (_: SecurityException) {
        }
    }

    open fun show(text: String, briefing: Boolean) {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // RemoteInput fills the reply into this intent, so it has to stay mutable.
        val reply = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        val later = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_LATER).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setAllowGeneratedReplies(false)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.her_accent))
            .setContentTitle("Her")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(0, "Later", later)
            .addAction(0, "Open", open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    companion object {
        const val CHANNEL = "her"
        const val REMINDER_CHANNEL = "her-reminders"
        const val NOTIFICATION_ID = 17
        const val REMINDER_NOTIFICATION_BASE = 1000
        const val ACTION_REPLY = "com.her.NOTIFY_REPLY"
        const val ACTION_LATER = "com.her.NOTIFY_LATER"
        const val KEY_REPLY = "reply"
        const val EXTRA_TEXT = "text"
    }
}

/** What a notification action asks for, decided without Android or the database. */
sealed class NotificationAction {
    data class Reply(val text: String) : NotificationAction()
    data class Later(val description: String) : NotificationAction()
    data object Ignore : NotificationAction()
}

object NotificationActions {
    fun decide(action: String?, replyText: CharSequence?, notificationText: String?): NotificationAction = when (action) {
        Notifier.ACTION_REPLY ->
            replyText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { NotificationAction.Reply(it) }
                ?: NotificationAction.Ignore
        Notifier.ACTION_LATER -> NotificationAction.Later(laterDescription(notificationText))
        else -> NotificationAction.Ignore
    }

    fun laterDescription(notificationText: String?): String {
        val text = notificationText?.trim().orEmpty()
        return if (text.isEmpty()) "Follow up later on the last notification." else "Follow up later: ${text.take(280)}"
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? com.her.HerApplication ?: return
        val graph = app.graph
        val decision = NotificationActions.decide(
            action = intent.action,
            replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY),
            notificationText = intent.getStringExtra(Notifier.EXTRA_TEXT),
        )
        if (decision != NotificationAction.Ignore) {
            val pending = goAsync()
            graph.io.execute {
                try {
                    kotlinx.coroutines.runBlocking {
                        when (decision) {
                            is NotificationAction.Reply -> {
                                graph.orchestrator.enqueueUserMessage(decision.text)
                                graph.scheduler.enqueueOutbox()
                                graph.repo.logActivity("notify", "Reply from notification queued.")
                            }
                            is NotificationAction.Later -> {
                                val now = graph.repo.clock.nowMillis()
                                graph.repo.saveAgentQueue(
                                    com.her.domain.AgentQueueItem(
                                        id = com.her.core.newId(),
                                        description = decision.description,
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
                            NotificationAction.Ignore -> Unit
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
        NotificationManagerCompat.from(context).cancel(Notifier.NOTIFICATION_ID)
    }
}
