package com.her.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.her.HerApplication
import com.her.core.newId
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.domain.MemorySource
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.ShortTermMemory
import com.her.notify.Notifier
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private const val MINUTE_MS = 60 * 1000L

/**
 * A pomodoro run: work, break, work, …, work. It lives on this device only; the phone that started it rings it.
 * [phase] counts from 0, so even phases are work and odd phases are breaks.
 */
data class FocusSession(
    val label: String,
    val taskId: String?,
    val workMin: Int,
    val breakMin: Int,
    val rounds: Int,
    val phase: Int,
    val phaseEndsAt: Long,
    val startedAt: Long,
    val breakMessage: String? = null,
    val backMessage: String? = null,
    val doneMessage: String? = null,
) {
    val isWork: Boolean get() = phase % 2 == 0

    /** The work round this phase is, or the one this break follows. */
    val round: Int get() = phase / 2 + 1

    /** Work rounds finished if the session stopped now. */
    val completedRounds: Int get() = if (isWork) round - 1 else round

    /** The phase after this one, starting at [at], or null when this was the last work round. */
    fun next(at: Long): FocusSession? {
        if (phase >= rounds * 2 - 2) return null
        val following = phase + 1
        val minutes = if (following % 2 == 0) workMin else breakMin
        return copy(phase = following, phaseEndsAt = at + minutes * MINUTE_MS)
    }

    fun toJson(): String = JSONObject()
        .put("label", label).put("taskId", taskId).put("workMin", workMin).put("breakMin", breakMin)
        .put("rounds", rounds).put("phase", phase).put("phaseEndsAt", phaseEndsAt).put("startedAt", startedAt)
        .put("breakMessage", breakMessage).put("backMessage", backMessage).put("doneMessage", doneMessage)
        .toString()

    companion object {
        fun fromJson(raw: String?): FocusSession? = runCatching {
            val json = JSONObject(raw ?: return null)
            fun text(key: String) = if (json.isNull(key)) null else json.optString(key).ifBlank { null }
            FocusSession(
                label = json.getString("label"),
                taskId = text("taskId"),
                workMin = json.getInt("workMin"),
                breakMin = json.getInt("breakMin"),
                rounds = json.getInt("rounds"),
                phase = json.getInt("phase"),
                phaseEndsAt = json.getLong("phaseEndsAt"),
                startedAt = json.getLong("startedAt"),
                breakMessage = text("breakMessage"),
                backMessage = text("backMessage"),
                doneMessage = text("doneMessage"),
            )
        }.getOrNull()
    }
}

/** The one alarm at the end of the current phase. */
interface FocusAlarm {
    fun arm(at: Long)
    fun cancel()
    fun exactAllowed(): Boolean
}

/** Runs a focus session: arms each phase, says the phase messages, and remembers what was done. No model calls. */
class FocusTimer(
    private val settings: AppSettingsStore,
    private val repo: HerRepository,
    private val notifier: Notifier,
    private val alarm: FocusAlarm,
) {
    fun current(): FocusSession? = settings.read().focus

    fun exactAllowed(): Boolean = alarm.exactAllowed()

    /** Starts a session, replacing any that is running. */
    suspend fun start(
        label: String,
        taskId: String?,
        workMin: Int,
        breakMin: Int,
        rounds: Int,
        breakMessage: String?,
        backMessage: String?,
        doneMessage: String?,
    ): FocusSession {
        current()?.let { finish(it, it.completedRounds, natural = false) }
        val now = repo.clock.nowMillis()
        val session = FocusSession(
            label, taskId, workMin, breakMin, rounds, phase = 0, phaseEndsAt = now + workMin * MINUTE_MS, startedAt = now,
            breakMessage = breakMessage, backMessage = backMessage, doneMessage = doneMessage,
        )
        settings.update { it.copy(focus = session) }
        alarm.arm(session.phaseEndsAt)
        notifier.showFocus(session, null, alert = false)
        repo.logActivity("focus", "Started $rounds × $workMin min: $label")
        return session
    }

    /** The phase alarm rang. An early or repeated ring re-arms instead of skipping ahead. */
    suspend fun onAlarm() {
        val session = current() ?: return
        val now = repo.clock.nowMillis()
        if (now < session.phaseEndsAt) {
            alarm.arm(session.phaseEndsAt)
            return
        }
        advance(session, now)
    }

    suspend fun skip() {
        current()?.let { advance(it, repo.clock.nowMillis()) }
    }

    /** Ends the session early; finished rounds are still remembered. */
    suspend fun stop(): FocusSession? {
        val session = current() ?: return null
        finish(session, session.completedRounds, natural = false)
        return session
    }

    /** Alarms do not survive a reboot. */
    fun rearm() {
        current()?.let {
            alarm.arm(it.phaseEndsAt)
            notifier.showFocus(it, null, alert = false)
        }
    }

    private suspend fun advance(session: FocusSession, now: Long) {
        val next = session.next(now) ?: return finish(session, session.rounds, natural = true)
        settings.update { it.copy(focus = next) }
        alarm.arm(next.phaseEndsAt)
        val text = if (next.isWork) {
            next.backMessage ?: "Back to it: round ${next.round} of ${next.rounds}."
        } else {
            next.breakMessage ?: "Break for ${next.breakMin} minutes."
        }
        say(text)
        notifier.showFocus(next, text, alert = true)
    }

    private suspend fun finish(session: FocusSession, completed: Int, natural: Boolean) {
        settings.update { it.copy(focus = null) }
        alarm.cancel()
        notifier.clearFocus()
        val now = repo.clock.nowMillis()
        if (completed > 0) {
            val day = Instant.ofEpochMilli(session.startedAt).atZone(repo.profileZone())
                .format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))
            repo.upsertShort(
                ShortTermMemory(
                    id = newId(),
                    content = "Focused $completed × ${session.workMin} min on “${session.label}” ($day).",
                    type = "focus",
                    confidence = 1.0,
                    importance = 0.4,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = null,
                    sourceMessageId = null,
                    source = MemorySource.SYSTEM,
                    metadataJson = session.taskId?.let { JSONObject().put("taskId", it).toString() },
                    deviceId = repo.deviceId,
                    version = 1,
                    deletedAt = null,
                ),
            )
        }
        repo.logActivity("focus", "Ended after $completed of ${session.rounds}: ${session.label}")
        if (natural) {
            val text = session.doneMessage ?: "That's ${session.rounds} rounds on ${session.label}."
            say(text)
            notifier.show(text, briefing = false)
        }
    }

    /** Saved as her latest message, so opening the app shows what the notification said. */
    private suspend fun say(text: String) {
        repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, text, MessageStatus.SENT, """{"focus":true}"""))
    }
}

class AndroidFocusAlarm(private val context: Context) : FocusAlarm {
    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    override fun exactAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager?.canScheduleExactAlarms() == true

    override fun arm(at: Long) {
        val manager = manager ?: return
        val tick = FocusReceiver.intent(context, FocusReceiver.ACTION_TICK)
        val exact = exactAllowed() && runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tick) }.isSuccess
        if (!exact) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tick)
    }

    override fun cancel() {
        manager?.cancel(FocusReceiver.intent(context, FocusReceiver.ACTION_TICK))
    }
}

/** The phase alarm, and the Skip and Stop buttons on the focus notification. */
class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as? HerApplication)?.graph ?: return
        val pending = goAsync()
        graph.io.execute {
            try {
                runBlocking {
                    when (intent.action) {
                        ACTION_TICK -> graph.focus.onAlarm()
                        ACTION_SKIP -> graph.focus.skip()
                        ACTION_STOP -> graph.focus.stop()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.her.FOCUS_TICK"
        const val ACTION_SKIP = "com.her.FOCUS_SKIP"
        const val ACTION_STOP = "com.her.FOCUS_STOP"

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FocusReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
