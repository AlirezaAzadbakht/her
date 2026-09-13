package com.her.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.her.HerApplication
import com.her.R
import com.her.core.nowMillis
import com.her.di.AppGraph
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class HourlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        runCatching { becomeForeground(NOTIF_HOURLY) }
        return try {
            if (!manual) {
                val app = graph.settings.read()
                val verdict = BackgroundRunPolicy.decideHourly(
                    llmConfigured = graph.secure.read().isConfigured,
                    lastSuccessAt = app.lastHourlyRunAt,
                    nowMs = nowMillis(),
                    inQuietHours = app.quietHours.contains(LocalTime.now()),
                    hourlyDuringQuietHours = app.hourlyDuringQuietHours,
                    power = readDevicePower(applicationContext),
                )
                if (verdict.decision != RunDecision.RUN) {
                    graph.repo.logActivity("hourly", verdict.reason)
                    return Result.success()
                }
            }
            graph.orchestrator.runHourly()
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}

class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        var scheduleNext = !manual
        runCatching { becomeForeground(NOTIF_NIGHTLY) }
        try {
            if (!manual) {
                val app = graph.settings.read()
                val verdict = BackgroundRunPolicy.decideNightly(
                    llmConfigured = graph.secure.read().isConfigured,
                    alreadyCompletedToday = app.lastNightlyDate == LocalDate.now().toString(),
                    power = readDevicePower(applicationContext),
                )
                when (verdict.decision) {
                    RunDecision.SKIP -> {
                        graph.repo.logActivity("nightly", verdict.reason)
                        return Result.success()
                    }
                    RunDecision.DEFER -> {
                        graph.repo.logActivity("nightly", verdict.reason)
                        scheduleNext = false
                        return Result.retry()
                    }
                    RunDecision.RUN -> { }
                }
            }
            graph.orchestrator.runNightly()
            return Result.success()
        } catch (_: Exception) {
            return Result.success()
        } finally {
            if (scheduleNext) graph.scheduler.enqueueNextNightly()
        }
    }
}

class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        var scheduleNext = !manual
        runCatching { becomeForeground(NOTIF_BRIEFING) }
        try {
            if (!manual) {
                val app = graph.settings.read()
                val verdict = BackgroundRunPolicy.decideBriefing(
                    llmConfigured = graph.secure.read().isConfigured,
                    alreadyCompletedToday = app.lastBriefingDate == LocalDate.now().toString(),
                    power = readDevicePower(applicationContext),
                )
                if (verdict.decision != RunDecision.RUN) {
                    graph.repo.logActivity("briefing", verdict.reason)
                    return Result.success()
                }
            }
            graph.orchestrator.runBriefing()
            return Result.success()
        } catch (_: Exception) {
            return Result.success()
        } finally {
            if (scheduleNext) graph.scheduler.enqueueNextBriefing()
        }
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        return try {
            val result = graph.orchestrator.processOutbox()
            if (result?.failed == true) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        val verdict = BackgroundRunPolicy.decideSync(
            driveEnabled = graph.settings.read().driveEnabled,
            power = readDevicePower(applicationContext),
        )
        if (verdict.decision != RunDecision.RUN) return Result.success()
        val auth = graph.googleAuth.authorize(drive = true, calendar = false)
        val token = auth.accessToken ?: return Result.success()
        return try {
            graph.syncEngine.sync(token)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}

private fun CoroutineWorker.graph(): AppGraph? =
    (applicationContext as? HerApplication)?.graph

private suspend fun CoroutineWorker.becomeForeground(notificationId: Int) {
    val context = applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                WORK_CHANNEL,
                context.getString(R.string.channel_her_work),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            },
        )
    }
    val notification = NotificationCompat.Builder(context, WORK_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(context.getString(R.string.work_running))
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    setForeground(
        ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        ),
    )
}

internal fun readDevicePower(context: Context): DevicePower {
    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 100
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return DevicePower(percent, charging)
}

class Scheduler(private val context: Context, private val settings: com.her.data.secure.AppSettingsStore) {
    fun enqueueAll() {
        val wm = WorkManager.getInstance(context)
        val fingerprint = BackgroundRunPolicy.scheduleFingerprint(settings.read().quietHours.endLocalTime())
        val changed = fingerprint != settings.readScheduleFingerprint()
        val periodicPolicy = if (changed) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(
            HOURLY,
            periodicPolicy,
            PeriodicWorkRequestBuilder<HourlyWorker>(1, TimeUnit.HOURS)
                .setConstraints(networkAndBattery())
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            SYNC,
            periodicPolicy,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkAndBattery())
                .build(),
        )
        enqueueDailyIfNeeded(NIGHTLY, changed) {
            OneTimeWorkRequestBuilder<NightlyWorker>()
                .setInitialDelay(delayUntil(BackgroundRunPolicy.NIGHTLY_AT), TimeUnit.MILLISECONDS)
                .setConstraints(networkOnly())
                .build()
        }
        val quietEnd = settings.read().quietHours.endLocalTime()
        enqueueDailyIfNeeded(BRIEFING, changed) {
            OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInitialDelay(delayUntil(quietEnd), TimeUnit.MILLISECONDS)
                .setConstraints(networkOnly())
                .build()
        }
        settings.writeScheduleFingerprint(fingerprint)
        enqueueOutbox()
    }

    fun enqueueNextNightly() {
        enqueueDailySuccessor(NIGHTLY) {
            OneTimeWorkRequestBuilder<NightlyWorker>()
                .setInitialDelay(delayUntil(BackgroundRunPolicy.NIGHTLY_AT), TimeUnit.MILLISECONDS)
                .setConstraints(networkOnly())
                .build()
        }
    }

    fun enqueueNextBriefing() {
        val quietEnd = settings.read().quietHours.endLocalTime()
        enqueueDailySuccessor(BRIEFING) {
            OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInitialDelay(delayUntil(quietEnd), TimeUnit.MILLISECONDS)
                .setConstraints(networkOnly())
                .build()
        }
    }

    fun enqueueOutbox() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            OUTBOX,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(networkOnly())
                .build(),
        )
    }

    fun runHourlyNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "hourly-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HourlyWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(networkOnly())
                .build(),
        )
    }

    fun runNightlyNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "nightly-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NightlyWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(networkOnly())
                .build(),
        )
    }

    fun runSyncNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }

    private fun enqueueDailyIfNeeded(
        name: String,
        force: Boolean,
        request: () -> androidx.work.OneTimeWorkRequest,
    ) {
        val wm = WorkManager.getInstance(context)
        val active = uniqueWorkIsActive(wm, name)
        if (active && !force) return
        if (force) wm.cancelUniqueWork(name)
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request())
    }

    private fun enqueueDailySuccessor(name: String, request: () -> androidx.work.OneTimeWorkRequest) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            name,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(),
        )
    }

    private fun uniqueWorkIsActive(wm: WorkManager, name: String): Boolean {
        return try {
            wm.getWorkInfosForUniqueWork(name).get().any { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun delayUntil(time: LocalTime): Long {
        val now = ZonedDateTime.now()
        var target = now.with(time).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }

    companion object {
        const val HOURLY = "her-hourly"
        const val NIGHTLY = "her-nightly"
        const val BRIEFING = "her-briefing"
        const val SYNC = "her-sync"
        const val OUTBOX = "her-outbox"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? HerApplication ?: return
        app.graph.scheduler.enqueueAll()
    }
}

class HerWorkerFactory(private val graphProvider: () -> AppGraph) : androidx.work.WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): androidx.work.ListenableWorker? {
        return when (workerClassName) {
            HourlyWorker::class.java.name -> HourlyWorker(appContext, workerParameters)
            NightlyWorker::class.java.name -> NightlyWorker(appContext, workerParameters)
            BriefingWorker::class.java.name -> BriefingWorker(appContext, workerParameters)
            SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters)
            OutboxWorker::class.java.name -> OutboxWorker(appContext, workerParameters)
            else -> null
        }
    }
}

internal const val KEY_MANUAL = "manual"
internal const val WORK_CHANNEL = "her-work"
internal const val NOTIF_HOURLY = 41
internal const val NOTIF_NIGHTLY = 42
internal const val NOTIF_BRIEFING = 43

private fun networkOnly(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

private fun networkAndBattery(): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()
