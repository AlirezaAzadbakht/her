package com.her

import android.app.Application
import androidx.work.Configuration
import com.her.di.AppGraph
import com.her.reminders.rearmAll
import com.her.work.HerWorkerFactory
import kotlinx.coroutines.runBlocking

class HerApplication : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scheduler.enqueueAll()
        // Alarms are lost on reboot and force-stop; an overdue one armed here rings right away.
        graph.io.execute {
            runBlocking { runCatching { graph.reminderAlarms.rearmAll(graph.repo) } }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HerWorkerFactory { graph })
            .build()
}
