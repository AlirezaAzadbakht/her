package com.her.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.her.HerApplication
import com.her.core.redactSecrets
import com.her.data.secure.LlmSettings
import com.her.domain.ChatMessage
import com.her.domain.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HerViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as HerApplication).graph

    val messages: StateFlow<List<ChatMessage>> = graph.repo.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profile: StateFlow<UserProfile?> = graph.repo.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings = graph.settings.state

    val sending = MutableStateFlow(false)
    val sendError = MutableStateFlow<String?>(null)
    val connectionMessage = MutableStateFlow<String?>(null)
    val googleMessage = MutableStateFlow<String?>(null)
    val pendingShare = MutableStateFlow<String?>(null)

    val people = graph.repo.observePeople().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val projects = graph.repo.observeProjects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val goals = graph.repo.observeGoals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks = graph.repo.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val commitments = graph.repo.observeCommitments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val openLoops = graph.repo.observeOpenLoops().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val routines = graph.repo.observeRoutines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val groceries = graph.repo.observeGroceries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dates = graph.repo.observeImportantDates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shortMemories = graph.repo.observeShort().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val longMemories = graph.repo.observeLong().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activity = graph.repo.observeActivity().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val debugEvents = graph.repo.observeDebug().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runs = graph.repo.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usage = graph.repo.observeUsageToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun llmSettings(): LlmSettings = graph.secure.read()

    fun saveLlm(settings: LlmSettings) {
        graph.secure.write(settings)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || sending.value) return
        sending.value = true
        sendError.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                graph.orchestrator.handleUserMessage(trimmed, pendingShare.value?.let { JSONObject().put("shared", it).toString() })
            }
            pendingShare.value = null
            sending.value = false
            if (result.failed) sendError.value = result.error
        }
    }

    fun consumeShare(shared: String) {
        pendingShare.value = shared
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) { graph.repo.deleteMessage(id) }
    }

    fun forgetFromMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.repo.activeLong().filter { it.sourceMessageId == id }.forEach { graph.repo.deleteLong(it.id) }
            graph.repo.activeShort().filter { it.sourceMessageId == id }.forEach { graph.repo.deleteShort(it.id) }
            graph.repo.logActivity("memory", "Forgot information from message $id")
        }
    }

    fun deleteLong(id: String) {
        viewModelScope.launch(Dispatchers.IO) { graph.repo.deleteLong(id) }
    }

    fun deleteShort(id: String) {
        viewModelScope.launch(Dispatchers.IO) { graph.repo.deleteShort(id) }
    }

    fun updateLongContent(id: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.repo.getLong(id)?.let {
                graph.repo.upsertLong(it.copy(content = content, updatedAt = System.currentTimeMillis(), version = it.version + 1))
            }
        }
    }

    fun testConnection() {
        connectionMessage.value = "Checking…"
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { graph.llm.ping(graph.secure.read()) }
            }
            connectionMessage.value = result.fold(
                onSuccess = {
                    graph.settings.update { s -> s.copy(apiConfiguredOnce = true) }
                    graph.orchestrator.seedOnboardingIfNeeded()
                    "Connected."
                },
                onFailure = { redactSecrets(it.message ?: "Could not connect") },
            )
        }
    }

    fun updateSettings(transform: (com.her.data.secure.AppSettings) -> com.her.data.secure.AppSettings) {
        graph.settings.update(transform)
    }

    fun runHourly() = graph.scheduler.runHourlyNow()
    fun runNightly() = graph.scheduler.runNightlyNow()
    fun runSync() = graph.scheduler.runSyncNow()

    fun connectGoogle() {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                graph.googleAuth.authorize(drive = true, calendar = true)
            }
            googleMessage.value = outcome.message
        }
    }

    suspend fun latestPrompt(): String = withContext(Dispatchers.IO) {
        graph.repo.recentDebug(8).firstOrNull { it.kind == "prompt" }?.payload ?: "No prompt captured yet."
    }
}
