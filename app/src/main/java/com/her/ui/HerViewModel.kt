package com.her.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.her.HerApplication
import com.her.R
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.runner.Receipt
import com.her.agent.runner.Receipts
import com.her.agent.runner.TurnResult
import com.her.agent.runner.TurnState
import com.her.core.redactSecrets
import com.her.core.nowMillis
import com.her.data.backup.Backup
import com.her.data.secure.LlmSettings
import com.her.domain.ChatMessage
import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.domain.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

class HerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as HerApplication).graph

    val latestAssistant: StateFlow<ChatMessage?> = graph.repo.observeLatestAssistant()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<UserProfile?> = graph.repo.observeProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val feeling: StateFlow<HerFeeling?> = graph.repo.observeFeeling()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val feelingBlend: StateFlow<List<Pair<Feeling, Double>>> = graph.repo.observeEmotionalBlend()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val persona: StateFlow<List<com.her.data.db.PersonaTrait>> = graph.repo.observePersona()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingCount: StateFlow<Int> = graph.repo.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val online: StateFlow<Boolean> = graph.connectivity.online
    val turnState: StateFlow<TurnState> = graph.orchestrator.turnState
    val settings = graph.settings.state
    val sqlBrowser = graph.sqlBrowser
    val memoryState = com.her.ui.memory.MemoryScreenState()

    val notice = MutableStateFlow<TurnResult?>(null)
    val connectionMessage = MutableStateFlow<String?>(null)
    /** Whether the last Save & test reached the model; screens read this, not the wording of the message. */
    val connectionOk = MutableStateFlow(false)
    val connectionDetail = MutableStateFlow<String?>(null)
    val checkingConnection = MutableStateFlow(false)
    val backupBusy = MutableStateFlow(false)
    val backupMessage = MutableStateFlow<String?>(null)
    val pendingShare = MutableStateFlow<String?>(null)
    private var turnJob: Job? = null
    private var backupJob: Job? = null

    val activity = graph.repo.observeActivity().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val debugEvents = graph.repo.observeDebug().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runs = graph.repo.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usage = graph.repo.observeUsageToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            graph.connectivity.online.collect { on ->
                if (!on) return@collect
                val result = withContext(Dispatchers.IO) { graph.orchestrator.processOutbox() }
                showOutcome(result)
            }
        }
    }

    /** A later success clears the notice, so it never outlives the message it was about. */
    private fun showOutcome(result: TurnResult?) {
        notice.value = result?.takeIf { it.failed }
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

    /** The app's own language, which follows the phone (or the per-app choice on Android 13+). */
    private fun persianUi(): Boolean = app.resources.configuration.locales[0].language == "fa"

    fun saveAndTest(settings: LlmSettings) {
        if (checkingConnection.value) return
        checkingConnection.value = true
        connectionOk.value = false
        connectionMessage.value = app.getString(R.string.checking)
        connectionDetail.value = null
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
                    connectionMessage.value = app.getString(R.string.connected)
                    connectionOk.value = true
                    // A message stuck on the old settings can go now.
                    if (notice.value != null) retry()
                },
                onFailure = {
                    connectionMessage.value = AgentOrchestrator.failureOf(it).let { f -> if (persianUi()) f.persian else f.english }
                    connectionDetail.value = redactSecrets(it.message ?: it::class.java.simpleName)
                },
            )
            checkingConnection.value = false
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        runPending {
            graph.orchestrator.enqueueUserMessage(
                trimmed,
                pendingShare.value?.let { JSONObject().put("shared", it).toString() },
            )
            pendingShare.value = null
        }
    }

    /** Sends whatever a failed turn left waiting. */
    fun retry() = runPending {}

    private fun runPending(before: suspend () -> Unit) {
        notice.value = null
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                before()
                if (graph.connectivity.online.value) {
                    showOutcome(graph.orchestrator.processOutbox())
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
            if (reversed.size < targets.size) {
                notice.value = TurnResult(
                    assistantText = null,
                    failed = true,
                    error = null,
                    callsUsed = 0,
                    runId = "",
                    notice = app.getString(R.string.undo_failed),
                )
            }
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

    fun updateSettings(transform: (com.her.data.secure.AppSettings) -> com.her.data.secure.AppSettings) {
        val before = graph.settings.read().quietHours
        graph.settings.update(transform)
        if (before != graph.settings.read().quietHours) {
            viewModelScope.launch(Dispatchers.IO) { graph.scheduler.enqueueAll() }
        }
    }

    fun runHourly() = graph.scheduler.runHourlyNow()
    fun runNightly() = graph.scheduler.runNightlyNow()
    fun saveBackup(uri: Uri) = backupJob {
        withContext(Dispatchers.IO) {
            val out = app.contentResolver.openOutputStream(uri) ?: error(app.getString(R.string.file_open_failed))
            out.use { Backup.write(app, graph.db, graph.settings.read(), it) }
        }
        backupMessage.value = app.getString(R.string.backup_saved)
    }

    fun shareBackup() = backupJob {
        val file = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
            File(dir, backupFileName()).also { f -> f.outputStream().use { Backup.write(app, graph.db, graph.settings.read(), it) } }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.share_backup)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands a Markdown file of what she knows to another app, for reading rather than restoring. */
    fun shareReadable() = backupJob {
        val file = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
            File(dir, "her-${LocalDate.now()}.md").also { it.writeText(com.her.data.backup.ReadableExport.markdown(graph.repo)) }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.share_readable)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Replaces everything on this phone with the backup, then restarts the app to open it. */
    fun importBackup(uri: Uri) = backupJob {
        withContext(Dispatchers.IO) {
            val input = app.contentResolver.openInputStream(uri) ?: error(app.getString(R.string.file_open_failed))
            input.use { Backup.stage(app, it) }
        }
        Backup.restart(app)
    }

    private fun backupJob(block: suspend () -> Unit) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupBusy.value = true
            backupMessage.value = null
            try {
                block()
            } catch (e: Exception) {
                backupMessage.value = e.message ?: app.getString(R.string.backup_failed)
            } finally {
                backupBusy.value = false
            }
        }
    }
}

fun backupFileName(): String = "her-backup-${LocalDate.now()}.zip"

/** Zip types file pickers report; some label a zip as plain bytes. */
val BACKUP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
