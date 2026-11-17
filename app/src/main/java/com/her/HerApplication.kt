package com.her

import android.app.Application
import androidx.work.Configuration
import com.her.di.AppGraph
import com.her.reminders.rearmAll
import com.her.work.HerWorkerFactory
import com.her.widget.HerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
        // Every reply, proactive line, and background message is a row here, so one watcher keeps the widget current.
        graph.repo.observeLatestAssistant()
            .distinctUntilChangedBy { it?.id to it?.updatedAt }
            .onEach { HerWidget.refresh(this, it?.content) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HerWorkerFactory { graph })
            .build()
}
