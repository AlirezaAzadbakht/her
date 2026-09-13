package com.her.agent.runner

import com.her.agent.prompt.ContextBuilder
import com.her.agent.prompt.Identity
import com.her.agent.tools.ToolRegistry
import com.her.core.redactSecrets
import com.her.data.remote.LlmClient
import com.her.data.remote.LlmException
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.AgentQueueItem
import com.her.domain.AgentRun
import com.her.domain.AgentRunStatus
import com.her.domain.AgentRunType
import com.her.domain.ChatMessage
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.QueueStatus
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** The next call is the last one; it should produce a reply instead of more tool calls. */
    fun isFinalCall(): Boolean = maxCalls > 1 && maxCalls - used == 1

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
    private val retryDelayMs: (attempt: Int) -> Long = { attempt -> 1000L shl attempt },
) {
    private val outboxMutex = Mutex()
    private val _turnState = MutableStateFlow<TurnState>(TurnState.Idle)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    private fun nowMillis(): Long = repo.clock.nowMillis()

    suspend fun enqueueUserMessage(text: String, metadataJson: String? = null): ChatMessage {
        val message = repo.newChatMessage(MessageRole.USER, text, MessageStatus.PENDING, metadataJson)
        repo.saveMessage(message, enqueueSync = false)
        return message
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
        if (result.assistantText.isNullOrBlank()) {
            repo.logActivity("hourly", "No notification sent.")
        }
        return result
    }

    suspend fun runNightly(): TurnResult {
        val today = repo.today().toString()
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
        val today = repo.today().toString()
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

    private suspend fun runTurn(
        type: AgentRunType,
        budget: CallBudget,
        latestUserText: String?,
        extraSystem: String?,
        persistAssistant: Boolean,
        allowNotify: Boolean,
        streamToUi: Boolean = false,
    ): TurnResult = withContext(Dispatchers.IO) {
        val runId = com.her.core.newId()
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
                // On the last call, withhold tools so the model has to answer instead of ending silently.
                val roundTools = if (budget.isFinalCall()) emptyList() else tools.specs()
                budget.consume()
                val round = StringBuilder()
                val response = callWithRetry(type) {
                    round.clear()
                    if (streamToUi) {
                        llm.stream(configured, messages, roundTools) { delta ->
                            round.append(delta)
                            val display = if (preamble.isNotEmpty()) "$preamble\n\n$round" else round.toString()
                            _turnState.value = TurnState.Streaming(display)
                        }
                    } else {
                        llm.complete(configured, messages, roundTools)
                    }
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
                if (streamToUi) {
                    _turnState.value = if (preamble.isNotBlank()) {
                        TurnState.Streaming(preamble)
                    } else {
                        TurnState.Thinking
                    }
                }
                messages += msg
                calls.forEach { call ->
                    val result = tools.execute(call.name, call.arguments)
                    repo.logDebug("tool", redactSecrets("${call.name} ${call.arguments} -> ${result.payloadJson}"))
                    messages += LlmMessage(
                        role = "tool",
                        content = forModel(result.payloadJson),
                        toolCallId = call.id,
                        name = call.name,
                    )
                }
            }
            if (lastText == null && preamble.isNotBlank()) {
                lastText = preamble
            }
            val cleaned = lastText?.takeIf { it.isNotBlank() && !Identity.isSilence(it) }
            if (persistAssistant && cleaned != null) {
                repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, cleaned, MessageStatus.SENT))
            }
            if (allowNotify && cleaned != null) {
                val decision = policy.shouldNotify(
                    key = "$type:${cleaned.take(40)}",
                    urgent = type == AgentRunType.BRIEFING,
                    now = repo.now(),
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = redactSecrets(e.message ?: "Unknown error")
            repo.saveRun(AgentRun(runId, type, started, nowMillis(), budget.used, AgentRunStatus.FAILED, error, null))
            repo.logActivity("error", error)
            if (persistAssistant) {
                repo.saveMessage(
                    repo.newChatMessage(
                        MessageRole.ASSISTANT,
                        "I couldn't reach the model just now. Your message is saved — try again in a moment.",
                        MessageStatus.FAILED,
                    ),
                )
            }
            TurnResult(null, true, error, budget.used, runId)
        } finally {
            if (streamToUi) _turnState.value = TurnState.Idle
        }
    }

    private suspend fun callWithRetry(type: AgentRunType, call: suspend () -> LlmResponse): LlmResponse {
        var attempt = 0
        while (true) {
            try {
                return call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                repo.recordUsage(type, 0, 0, 0, error = true)
                if (attempt >= MAX_RETRIES || !isRetryable(e)) throw e
                repo.logActivity("llm", "Retrying after: ${redactSecrets(e.message ?: e::class.java.simpleName)}")
                delay(retryDelayMs(attempt))
                attempt += 1
            }
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
        repo.saveMessage(
            repo.newChatMessage(MessageRole.ASSISTANT, "Hi, $user. I'm $her.", MessageStatus.SENT, """{"onboarding":true}"""),
        )
        val now = nowMillis()
        repo.saveAgentQueue(
            AgentQueueItem(
                id = com.her.core.newId(),
                description = "Learn timezone and what matters right now — slowly, not as an interview.",
                status = QueueStatus.OPEN,
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

    companion object {
        const val MAX_RETRIES = 2
        const val MAX_TOOL_PAYLOAD_CHARS = 8_000

        /** Rate limits, server errors, and plain network failures are worth another try. Config and parse errors are not. */
        internal fun isRetryable(e: IOException): Boolean = when (e) {
            is LlmException -> e.status == 429 || (e.status != null && e.status >= 500)
            else -> true
        }

        internal fun forModel(payload: String): String =
            if (payload.length <= MAX_TOOL_PAYLOAD_CHARS) {
                payload
            } else {
                payload.take(MAX_TOOL_PAYLOAD_CHARS) + "\n…[truncated ${payload.length - MAX_TOOL_PAYLOAD_CHARS} chars]"
            }
    }
}
