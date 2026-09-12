package com.her

import android.app.Application
import androidx.work.Configuration
import com.her.di.AppGraph
import com.her.work.HerWorkerFactory

class HerApplication : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scheduler.enqueueAll()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HerWorkerFactory { graph })
            .build()
}
