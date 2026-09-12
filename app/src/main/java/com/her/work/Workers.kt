package com.her.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.her.HerApplication
import com.her.di.AppGraph
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class HourlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        return try {
            val result = graph.orchestrator.runHourly()
            if (result.failed) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        return try {
            val result = graph.orchestrator.runNightly()
            if (result.failed) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        return try {
            graph.orchestrator.runBriefing()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = graph() ?: return Result.retry()
        if (!graph.settings.read().driveEnabled) return Result.success()
        val auth = graph.googleAuth.authorize(drive = true, calendar = false)
        val token = auth.accessToken ?: return Result.success()
        return try {
            graph.syncEngine.sync(token)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun CoroutineWorker.graph(): AppGraph? =
    (applicationContext as? HerApplication)?.graph

class Scheduler(private val context: Context, private val settings: com.her.data.secure.AppSettingsStore) {
    fun enqueueAll() {
        val wm = WorkManager.getInstance(context)
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            HOURLY,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HourlyWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            NIGHTLY,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<NightlyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(LocalTime.of(3, 0)), TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build(),
        )
        val quietEnd = settings.read().quietHours.endLocalTime()
        wm.enqueueUniquePeriodicWork(
            BRIEFING,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BriefingWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(quietEnd), TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(network)
                .build(),
        )
    }

    fun runHourlyNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "hourly-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HourlyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun runNightlyNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "nightly-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NightlyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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

    private fun delayUntil(time: LocalTime): Long {
        val now = ZonedDateTime.now()
        var target = now.with(time)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }

    companion object {
        const val HOURLY = "her-hourly"
        const val NIGHTLY = "her-nightly"
        const val BRIEFING = "her-briefing"
        const val SYNC = "her-sync"
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
            else -> null
        }
    }
}
