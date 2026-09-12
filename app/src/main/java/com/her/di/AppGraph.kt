package com.her.di

import android.content.Context
import androidx.room.Room
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.core.newId
import com.her.core.nowMillis
import com.her.core.ConnectivityObserver
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.db.SqlBrowser
import com.her.data.drive.SyncEngine
import com.her.data.google.GoogleAuthService
import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.retrieval.NoOpEmbeddingProvider
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.SecureSettingsStore
import com.her.domain.ChatMessage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import com.her.work.Scheduler
import java.util.concurrent.Executors

class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val io = Executors.newSingleThreadExecutor()

    val settings = AppSettingsStore(appContext)
    val secure = SecureSettingsStore(appContext)
    val connectivity = ConnectivityObserver(appContext)
    val db: HerDatabase = Room.databaseBuilder(appContext, HerDatabase::class.java, "her.db")
        .build()
    val repo = HerRepository(db, settings)
    val sqlBrowser = SqlBrowser(db)
    val ranker = HybridRanker(repo, NoOpEmbeddingProvider())
    val calendar = CalendarDataSource(appContext)
    val llm = LlmClient()
    val webSearch = WebSearchClient()
    val googleAuth = GoogleAuthService(appContext, settings)
    val syncEngine = SyncEngine(repo)
    val notifier = Notifier(appContext)
    val policy = NotificationPolicy(settings)
    val scheduler = Scheduler(appContext, settings)

    private val contextBuilder: ContextBuilder by lazy {
        ContextBuilder(repo, ranker, settings, calendar)
    }

    val tools: ToolRegistry by lazy {
        ToolRegistry(repo, ranker, settings, calendar, webSearch) { text ->
            val now = nowMillis()
            repo.saveMessage(
                ChatMessage(
                    id = newId(),
                    role = MessageRole.ASSISTANT,
                    content = text,
                    createdAt = now,
                    updatedAt = now,
                    deviceId = repo.deviceId,
                    version = 1,
                    deletedAt = null,
                    status = MessageStatus.SENT,
                    metadataJson = """{"proactive":true}""",
                ),
            )
        }
    }

    val orchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(repo, llm, { secure.read() }, settings, contextBuilder, tools, notifier, policy)
    }
}
