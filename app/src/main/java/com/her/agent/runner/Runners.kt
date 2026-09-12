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
import com.her.data.secure.SecureSettingsStore
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

class AgentOrchestrator(
    private val repo: HerRepository,
    private val llm: LlmClient,
    private val secure: SecureSettingsStore,
    private val settings: AppSettingsStore,
    private val contextBuilder: ContextBuilder,
    private val tools: ToolRegistry,
    private val notifier: Notifier,
    private val policy: NotificationPolicy,
) {
    suspend fun handleUserMessage(text: String, metadataJson: String? = null): TurnResult {
        val saved = persistUser(text, metadataJson)
        return runTurn(
            type = AgentRunType.CHAT,
            budget = CallBudget(settings.read().chatToolCallLimit.coerceAtLeast(1)),
            latestUserText = saved.content,
            extraSystem = null,
            persistAssistant = true,
            allowNotify = false,
        )
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
        settings.update { it.copy(lastHourlyRunAt = now) }
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
            status = MessageStatus.SENT,
            metadataJson = metadataJson,
        )
        repo.saveMessage(message)
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
    ): TurnResult = withContext(Dispatchers.IO) {
        val runId = newId()
        val started = nowMillis()
        repo.saveRun(AgentRun(runId, type, started, null, 0, AgentRunStatus.RUNNING, null, null))
        val llmSettings = secure.read()
        try {
            val built = contextBuilder.build(latestUserText)
            val messages = built.messages.toMutableList()
            if (extraSystem != null) {
                messages.add(1, LlmMessage(role = "system", content = extraSystem))
            }
            repo.logDebug("prompt", redactSecrets(built.systemBundle))
            repo.logDebug("retrieved", built.retrieved.joinToString("\n") { "${it.memoryType}:${it.content}" })
            var lastText: String? = null
            while (budget.canCall()) {
                budget.consume()
                val response = try {
                    llm.complete(llmSettings, messages, tools.specs())
                } catch (e: LlmException) {
                    repo.recordUsage(type, 0, 0, 0, error = true)
                    throw e
                }
                repo.recordUsage(type, response.usage.inputTokens, response.usage.outputTokens, response.usage.latencyMs, error = false)
                repo.logDebug("llm", redactSecrets(response.rawJson.take(8000)))
                val msg = response.message
                lastText = msg.content?.trim()
                val calls = msg.toolCalls.orEmpty()
                if (calls.isEmpty()) break
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
        }
    }

    suspend fun seedOnboardingIfNeeded() {
        if (settings.read().onboardingSeeded) return
        if (repo.recentMessages(1).isNotEmpty()) {
            settings.update { it.copy(onboardingSeeded = true) }
            return
        }
        val now = nowMillis()
        repo.saveMessage(
            ChatMessage(
                id = newId(),
                role = MessageRole.ASSISTANT,
                content = "Hi. Before we really start, what should I call you?",
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
                description = "Learn what they want to call me, then gather timezone and what matters right now — slowly, not as an interview.",
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
