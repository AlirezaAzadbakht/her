package com.her.ui

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.her.HerApplication
import com.her.agent.runner.Receipt
import com.her.agent.runner.Receipts
import com.her.agent.runner.TurnResult
import com.her.agent.runner.TurnState
import com.her.core.redactSecrets
import com.her.core.nowMillis
import com.her.data.secure.LlmSettings
import com.her.domain.ChatMessage
import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.domain.UserProfile
import com.her.reminders.rearmAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HerViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as HerApplication).graph

    val latestAssistant: StateFlow<ChatMessage?> = graph.repo.observeLatestAssistant()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<UserProfile?> = graph.repo.observeProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val feeling: StateFlow<HerFeeling?> = graph.repo.observeFeeling()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val feelingBlend: StateFlow<List<Pair<Feeling, Double>>> = graph.repo.observeEmotionalBlend()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingCount: StateFlow<Int> = graph.repo.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val online: StateFlow<Boolean> = graph.connectivity.online
    val turnState: StateFlow<TurnState> = graph.orchestrator.turnState
    val settings = graph.settings.state
    val sqlBrowser = graph.sqlBrowser

    val sendError = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<TurnResult?>(null)
    val connectionMessage = MutableStateFlow<String?>(null)
    val checkingConnection = MutableStateFlow(false)
    val googleMessage = MutableStateFlow<String?>(null)
    val googleConsent = MutableStateFlow<PendingIntent?>(null)
    val restoring = MutableStateFlow(false)
    val restoreMessage = MutableStateFlow<String?>(null)
    val pendingShare = MutableStateFlow<String?>(null)
    private var turnJob: Job? = null
    private var restoreJob: Job? = null

    val activity = graph.repo.observeActivity().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val debugEvents = graph.repo.observeDebug().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runs = graph.repo.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usage = graph.repo.observeUsageToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            graph.connectivity.online.collect { on ->
                if (!on) return@collect
                val result = withContext(Dispatchers.IO) { graph.orchestrator.processOutbox() }
                showFailure(result)
            }
        }
    }

    private fun showFailure(result: TurnResult?) {
        if (result?.failed != true) return
        if (result.notice != null) notice.value = result else sendError.value = result.error
    }

    fun saveUserName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = graph.repo.getProfile()
                graph.repo.saveProfile(
                    current.copy(userName = trimmed, updatedAt = nowMillis(), version = current.version + 1),
                )
            }
        }
    }

    fun saveAssistantName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = graph.repo.getProfile()
                graph.repo.saveProfile(
                    current.copy(assistantName = trimmed, updatedAt = nowMillis(), version = current.version + 1),
                )
            }
        }
    }

    fun llmSettings(): LlmSettings = graph.secure.read()

    fun saveLlm(settings: LlmSettings) {
        graph.secure.write(settings)
    }

    fun saveAndTest(settings: LlmSettings) {
        if (checkingConnection.value) return
        checkingConnection.value = true
        connectionMessage.value = "Checking…"
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    graph.secure.write(settings)
                    graph.llm.ping(graph.secure.read())
                }
            }
            result.fold(
                onSuccess = {
                    graph.settings.update { s -> s.copy(apiConfiguredOnce = true) }
                    runCatching {
                        withContext(Dispatchers.IO) { graph.orchestrator.seedOnboardingIfNeeded() }
                    }
                    connectionMessage.value = "Connected."
                },
                onFailure = {
                    connectionMessage.value = redactSecrets(it.message ?: "Could not connect")
                },
            )
            checkingConnection.value = false
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        sendError.value = null
        notice.value = null
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.orchestrator.enqueueUserMessage(
                    trimmed,
                    pendingShare.value?.let { JSONObject().put("shared", it).toString() },
                )
                pendingShare.value = null
                if (graph.connectivity.online.value) {
                    val result = graph.orchestrator.processOutbox()
                    showFailure(result)
                } else {
                    graph.scheduler.enqueueOutbox()
                }
            }
        }
    }

    fun cancelTurn() {
        turnJob?.cancel()
    }

    fun undoReceipts(messageId: String, receipts: List<Receipt>) {
        val targets = receipts.filter { it.undoable && !it.undone }
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val reversed = targets.filter { runCatching { graph.tools.undo(it.entityType, it.entityId) }.getOrDefault(false) }
            if (reversed.isEmpty()) return@launch
            graph.repo.getMessage(messageId)?.let { message ->
                graph.repo.saveMessage(
                    message.copy(
                        metadataJson = Receipts.markUndone(message.metadataJson, reversed),
                        updatedAt = nowMillis(),
                        version = message.version + 1,
                    ),
                )
            }
        }
    }

    fun consumeShare(shared: String) {
        pendingShare.value = shared
    }

    fun testConnection() {
        saveAndTest(graph.secure.read())
    }

    fun updateSettings(transform: (com.her.data.secure.AppSettings) -> com.her.data.secure.AppSettings) {
        val before = graph.settings.read().quietHours
        graph.settings.update(transform)
        if (before != graph.settings.read().quietHours) {
            viewModelScope.launch(Dispatchers.IO) { graph.scheduler.enqueueAll() }
        }
    }

    fun runHourly() = graph.scheduler.runHourlyNow()
    fun runNightly() = graph.scheduler.runNightlyNow()
    fun runSync() = graph.scheduler.runSyncNow()

    fun connectGoogle() {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                graph.googleAuth.authorize(drive = true, calendar = true)
            }
            googleConsent.value = outcome.pendingIntent
            googleMessage.value = outcome.message
        }
    }

    fun clearGoogleConsent() {
        googleConsent.value = null
    }

    fun finishGoogleConnect() {
        googleConsent.value = null
        connectGoogle()
    }

    fun restoreFromDrive() {
        if (restoreJob?.isActive == true) return
        restoreJob = viewModelScope.launch {
            restoring.value = true
            restoreMessage.value = null
            try {
                val outcome = withContext(Dispatchers.IO) {
                    graph.googleAuth.authorize(drive = true, calendar = false)
                }
                if (outcome.pendingIntent != null) {
                    googleConsent.value = outcome.pendingIntent
                    return@launch
                }
                val token = outcome.accessToken
                if (token.isNullOrBlank()) {
                    restoreMessage.value = outcome.message
                    return@launch
                }
                graph.settings.update { it.copy(driveEnabled = true) }
                val summary = withContext(Dispatchers.IO) {
                    val result = graph.syncEngine.sync(token)
                    graph.reminderAlarms.rearmAll(graph.repo)
                    result
                }
                restoreMessage.value = if (summary.applied == 0) {
                    "Nothing to restore in this Google account."
                } else {
                    "Restored ${summary.applied} items."
                }
            } catch (e: Exception) {
                restoreMessage.value = e.message ?: "Restore failed."
            } finally {
                if (googleConsent.value == null) restoring.value = false
            }
        }
    }
}
