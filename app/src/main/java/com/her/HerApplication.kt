package com.her

import android.app.Application
import androidx.work.Configuration
import com.her.di.AppGraph
import com.her.reminders.rearmAll
import com.her.work.HerWorkerFactory
import com.her.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

class HerApplication : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scheduler.enqueueAll()
        // Alarms are lost on reboot and force-stop; an overdue one armed here rings right away.
        graph.io.execute {
            runBlocking { runCatching { graph.reminderAlarms.rearmAll(graph.repo) } }
        }
        // Every reply, proactive line, background message and change of feeling passes through here,
        // so one watcher keeps the widgets current. The feeling projection re-stamps itself each minute,
        // so it is compared by what it shows, not by time.
        combine(graph.repo.observeLatestAssistant(), graph.repo.observeFeeling(), graph.repo.observeEmotionalBlend()) { message, feeling, blend ->
            Triple(message?.let { it.id to it.updatedAt }, feeling?.let { it.feeling to it.note }, blend.map { it.first })
        }
            .distinctUntilChanged()
            .onEach { Widgets.refreshAll(this) }
            .launchIn(scope)
    }

    /** The widgets' orb follows the light or dark palette, so redraw them when the phone switches. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch { Widgets.refreshAll(this@HerApplication) }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HerWorkerFactory { graph })
            .build()
}
