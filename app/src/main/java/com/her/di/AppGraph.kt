package com.her.di

import android.content.Context
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.core.ConnectivityObserver
import com.her.data.calendar.CalendarDataSource
import com.her.data.backup.Backup
import com.her.data.db.HerDatabase
import com.her.data.db.SqlBrowser
import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.retrieval.OpenAiEmbeddingProvider
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.SecureSettingsStore
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.feelings.FastAppraiser
import com.her.focus.AndroidFocusAlarm
import com.her.focus.FocusTimer
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import com.her.reminders.AndroidReminderAlarms
import com.her.reminders.ReminderDelivery
import com.her.reminders.SystemAlarmClock
import com.her.work.Scheduler
import java.util.concurrent.Executors

class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val io = Executors.newSingleThreadExecutor()

    val settings = AppSettingsStore(appContext)
    val secure = SecureSettingsStore(appContext)
    val connectivity = ConnectivityObserver(appContext)
    val db: HerDatabase = run {
        // An imported backup is swapped in here, before anything opens the database.
        Backup.applyStaged(appContext, settings)
        HerDatabase.build(appContext)
    }
    val repo = HerRepository(db, settings)
    val sqlBrowser = SqlBrowser(db)
    val ranker = HybridRanker(
        repo,
        OpenAiEmbeddingProvider(
            llmSettings = { secure.read() },
            enabledModel = { settings.read().takeIf { it.embeddingsEnabled }?.embeddingModel },
        ),
    )
    val calendar = CalendarDataSource(appContext)
    val llm = LlmClient()
    val webSearch = WebSearchClient(llm = llm, llmSettings = { secure.read() })
    val notifier = Notifier(appContext)
    val policy = NotificationPolicy(settings)
    val scheduler = Scheduler(appContext, settings)
    val reminderAlarms = AndroidReminderAlarms(appContext)
    val reminderDelivery = ReminderDelivery(repo, notifier)
    val focus = FocusTimer(settings, repo, notifier, AndroidFocusAlarm(appContext))

    private val contextBuilder: ContextBuilder by lazy {
        ContextBuilder(repo, ranker, settings, calendar)
    }

    val tools: ToolRegistry by lazy {
        ToolRegistry(repo, ranker, settings, calendar, webSearch, { text ->
            repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, text, MessageStatus.SENT, """{"proactive":true}"""))
        }, reminderAlarms, SystemAlarmClock(appContext), focus)
    }

    val orchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(repo, llm, { secure.read() }, settings, contextBuilder, tools, notifier, policy,
            appraiser = FastAppraiser(repo, llm) { secure.read() })
    }
}
