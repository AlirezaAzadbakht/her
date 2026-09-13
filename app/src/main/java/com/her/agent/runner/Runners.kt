package com.her.agent.runner

import com.her.agent.prompt.ContextBuilder
import com.her.agent.prompt.Identity
import com.her.agent.tools.ToolRegistry
import com.her.core.newId
import com.her.core.nowMillis
import com.her.core.redactSecrets
import com.her.data.remote.LlmClient
import com.her.data.remote.LlmException
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.AgentRun
import com.her.domain.AgentRunStatus
import com.her.domain.AgentRunType
import com.her.domain.ChatMessage
import com.her.domain.LlmMessage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CallBudget(val maxCalls: Int) {
    var used: Int = 0
        private set

    fun canCall(): Boolean = used < maxCalls

    fun consume() {
        used += 1
    }
}

data class TurnResult(
    val assistantText: String?,
    val failed: Boolean,
    val error: String?,
    val callsUsed: Int,
    val runId: String,
)

sealed class TurnState {
    data object Idle : TurnState()
    data object Thinking : TurnState()
    data class Streaming(val text: String) : TurnState()
}

class AgentOrchestrator(
    private val repo: HerRepository,
    private val llm: LlmClient,
    private val llmSettings: () -> LlmSettings,
    private val settings: AppSettingsStore,
    private val contextBuilder: ContextBuilder,
    private val tools: ToolRegistry,
    private val notifier: Notifier,
    private val policy: NotificationPolicy,
) {
    private val outboxMutex = Mutex()
    private val _turnState = MutableStateFlow<TurnState>(TurnState.Idle)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    suspend fun enqueueUserMessage(text: String, metadataJson: String? = null): ChatMessage {
        return persistUser(text, metadataJson)
    }

    suspend fun processOutbox(): TurnResult? = outboxMutex.withLock {
        val pending = repo.pendingUserMessages()
        if (pending.isEmpty()) return null
        val joined = pending.joinToString("\n\n") { it.content }
        val result = runTurn(
            type = AgentRunType.CHAT,
            budget = CallBudget(settings.read().chatToolCallLimit.coerceAtLeast(1)),
            latestUserText = joined,
            extraSystem = null,
            persistAssistant = true,
            allowNotify = false,
            streamToUi = true,
        )
        if (!result.failed) {
            repo.markSent(pending.map { it.id })
        }
        result
    }

    suspend fun runHourly(): TurnResult {
        val last = settings.read().lastHourlyRunAt
        val now = nowMillis()
        if (now - last < 20 * 60 * 1000 && last > 0) {
            repo.logActivity("hourly", "Skipped; ran recently.")
            return TurnResult(null, false, null, 0, "skipped")
        }
        val result = runTurn(
            type = if (now - last > 3 * 60 * 60 * 1000 && last > 0) AgentRunType.CATCH_UP else AgentRunType.HOURLY,
            budget = CallBudget(10),
            latestUserText = null,
            extraSystem = Identity.HOURLY_PROMPT,
            persistAssistant = false,
            allowNotify = true,
        )
        if (!result.failed) {
            settings.update { it.copy(lastHourlyRunAt = now) }
        }
        if (result.assistantText.isNullOrBlank() || result.assistantText == "NO_NOTIFICATION") {
            repo.logActivity("hourly", "No notification sent.")
        }
        return result
    }

    suspend fun runNightly(): TurnResult {
        val today = java.time.LocalDate.now().toString()
        if (settings.read().lastNightlyDate == today) {
            repo.logActivity("nightly", "Already completed for $today")
            return TurnResult(null, false, null, 0, "skipped")
        }
        val result = runTurn(
            type = AgentRunType.NIGHTLY,
            budget = CallBudget(50),
            latestUserText = null,
            extraSystem = Identity.NIGHTLY_PROMPT,
            persistAssistant = false,
            allowNotify = false,
        )
        if (!result.failed) {
            settings.update { it.copy(lastNightlyDate = today) }
            repo.logActivity("nightly", "Consolidation finished with ${result.callsUsed} calls.")
        } else {
            repo.logActivity("nightly", "Failed: ${result.error}")
        }
        return result
    }

    suspend fun runBriefing(): TurnResult {
        val today = java.time.LocalDate.now().toString()
        if (settings.read().lastBriefingDate == today) {
            repo.logActivity("briefing", "Already generated for $today")
            return TurnResult(null, false, null, 0, "skipped")
        }
        val result = runTurn(
            type = AgentRunType.BRIEFING,
            budget = CallBudget(8),
            latestUserText = null,
            extraSystem = Identity.BRIEFING_PROMPT,
            persistAssistant = true,
            allowNotify = true,
        )
        if (!result.failed && !result.assistantText.isNullOrBlank()) {
            settings.update { it.copy(lastBriefingDate = today) }
        }
        return result
    }

    private suspend fun persistUser(text: String, metadataJson: String?): ChatMessage {
        val now = nowMillis()
        val message = ChatMessage(
            id = newId(),
            role = MessageRole.USER,
            content = text,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
            status = MessageStatus.PENDING,
            metadataJson = metadataJson,
        )
        repo.saveMessage(message, enqueueSync = false)
        return message
    }

    private suspend fun persistAssistant(text: String): ChatMessage {
        val now = nowMillis()
        val message = ChatMessage(
            id = newId(),
            role = MessageRole.ASSISTANT,
            content = text,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
            status = MessageStatus.SENT,
            metadataJson = null,
        )
        repo.saveMessage(message)
        return message
    }

    private suspend fun runTurn(
        type: AgentRunType,
        budget: CallBudget,
        latestUserText: String?,
        extraSystem: String?,
        persistAssistant: Boolean,
        allowNotify: Boolean,
        streamToUi: Boolean = false,
    ): TurnResult = withContext(Dispatchers.IO) {
        val runId = newId()
        val started = nowMillis()
        repo.saveRun(AgentRun(runId, type, started, null, 0, AgentRunStatus.RUNNING, null, null))
        val configured = llmSettings()
        if (streamToUi) _turnState.value = TurnState.Thinking
        try {
            val built = contextBuilder.build(latestUserText)
            val messages = built.messages.toMutableList()
            if (extraSystem != null) {
                messages.add(1, LlmMessage(role = "system", content = extraSystem))
            }
            repo.logDebug("prompt", redactSecrets(built.systemBundle))
            repo.logDebug("retrieved", built.retrieved.joinToString("\n") { "${it.memoryType}:${it.content}" })
            var lastText: String? = null
            var preamble = ""
            while (budget.canCall()) {
                budget.consume()
                val round = StringBuilder()
                val response = try {
                    if (streamToUi) {
                        llm.stream(configured, messages, tools.specs()) { delta ->
                            round.append(delta)
                            val display = if (preamble.isNotEmpty()) "$preamble\n\n$round" else round.toString()
                            _turnState.value = TurnState.Streaming(display)
                        }
                    } else {
                        llm.complete(configured, messages, tools.specs())
                    }
                } catch (e: LlmException) {
                    repo.recordUsage(type, 0, 0, 0, error = true)
                    throw e
                }
                repo.recordUsage(type, response.usage.inputTokens, response.usage.outputTokens, response.usage.latencyMs, error = false)
                repo.logDebug("llm", redactSecrets(response.rawJson.take(8000)))
                val msg = response.message
                val roundText = msg.content?.trim().orEmpty().ifBlank { round.toString().trim() }
                val calls = msg.toolCalls.orEmpty()
                if (calls.isEmpty()) {
                    lastText = when {
                        preamble.isNotEmpty() && roundText.isNotBlank() -> "$preamble\n\n$roundText"
                        roundText.isNotBlank() -> roundText
                        preamble.isNotBlank() -> preamble
                        else -> null
                    }
                    break
                }
                if (roundText.isNotBlank()) {
                    preamble = if (preamble.isEmpty()) roundText else "$preamble\n\n$roundText"
                }
                if (streamToUi) _turnState.value = TurnState.Thinking
                messages += msg
                calls.forEach { call ->
                    val result = tools.execute(call.name, call.arguments)
                    repo.logDebug("tool", redactSecrets("${call.name} ${call.arguments} -> ${result.payloadJson}"))
                    messages += LlmMessage(
                        role = "tool",
                        content = result.payloadJson,
                        toolCallId = call.id,
                        name = call.name,
                    )
                }
            }
            val cleaned = lastText?.takeIf { it.isNotBlank() && it != "NO_NOTIFICATION" }
            if (persistAssistant && cleaned != null) {
                persistAssistant(cleaned)
            }
            if (allowNotify && cleaned != null) {
                val decision = policy.shouldNotify(
                    key = "$type:${cleaned.take(40)}",
                    urgent = type == AgentRunType.BRIEFING,
                    now = java.time.ZonedDateTime.now(),
                )
                if (decision.notify) {
                    notifier.show(cleaned, type == AgentRunType.BRIEFING)
                    settings.update { it.copy(lastNotificationKey = decision.key, lastNotificationAt = nowMillis()) }
                } else {
                    repo.logActivity("notify", "Suppressed: ${decision.reason}")
                }
            }
            repo.saveRun(AgentRun(runId, type, started, nowMillis(), budget.used, AgentRunStatus.COMPLETED, null, null))
            TurnResult(cleaned, false, null, budget.used, runId)
        } catch (e: Exception) {
            val error = redactSecrets(e.message ?: "Unknown error")
            repo.saveRun(AgentRun(runId, type, started, nowMillis(), budget.used, AgentRunStatus.FAILED, error, null))
            repo.logActivity("error", error)
            if (persistAssistant) {
                val now = nowMillis()
                repo.saveMessage(
                    ChatMessage(
                        id = newId(),
                        role = MessageRole.ASSISTANT,
                        content = "I couldn't reach the model just now. Your message is saved — try again in a moment.",
                        createdAt = now,
                        updatedAt = now,
                        deviceId = repo.deviceId,
                        version = 1,
                        deletedAt = null,
                        status = MessageStatus.FAILED,
                        metadataJson = null,
                    ),
                )
            }
            TurnResult(null, true, error, budget.used, runId)
        } finally {
            if (streamToUi) _turnState.value = TurnState.Idle
        }
    }

    suspend fun seedOnboardingIfNeeded() {
        if (settings.read().onboardingSeeded) return
        if (repo.recentMessages(1).isNotEmpty()) {
            settings.update { it.copy(onboardingSeeded = true) }
            return
        }
        val profile = repo.getProfile()
        val user = profile.userName?.trim().orEmpty().ifBlank { "there" }
        val her = profile.assistantName?.trim().orEmpty().ifBlank { "Her" }
        val now = nowMillis()
        repo.saveMessage(
            ChatMessage(
                id = newId(),
                role = MessageRole.ASSISTANT,
                content = "Hi, $user. I'm $her.",
                createdAt = now,
                updatedAt = now,
                deviceId = repo.deviceId,
                version = 1,
                deletedAt = null,
                status = MessageStatus.SENT,
                metadataJson = """{"onboarding":true}""",
            ),
        )
        repo.saveAgentQueue(
            com.her.domain.AgentQueueItem(
                id = newId(),
                description = "Learn timezone and what matters right now — slowly, not as an interview.",
                status = com.her.domain.QueueStatus.OPEN,
                priority = 0.8,
                dueAt = null,
                relatedEntityType = "onboarding",
                relatedEntityId = null,
                createdAt = now,
                updatedAt = now,
                deviceId = repo.deviceId,
                version = 1,
                deletedAt = null,
            ),
        )
        settings.update { it.copy(onboardingSeeded = true) }
    }
}
