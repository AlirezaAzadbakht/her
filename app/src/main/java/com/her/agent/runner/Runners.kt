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
import com.her.notify.NotifyDecision
import com.her.notify.Notifier
import com.her.notify.alreadySaidProactively
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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
    fun isFinalCall(): Boolean = canCall() && used == maxCalls - 1

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
    // ponytail: one lock for every run; a waiting chat makes a background run stop at its next round,
    // so chat waits at most one background call. Cancel that in-flight call too if one round is still too long.
    private val runMutex = Mutex()
    private val chatWaiting = AtomicInteger(0)
    private val _turnState = MutableStateFlow<TurnState>(TurnState.Idle)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    private fun nowMillis(): Long = repo.clock.nowMillis()

    suspend fun enqueueUserMessage(text: String, metadataJson: String? = null): ChatMessage {
        val message = repo.newChatMessage(MessageRole.USER, text, MessageStatus.PENDING, metadataJson)
        repo.saveMessage(message, enqueueSync = false)
        return message
    }

    suspend fun processOutbox(): TurnResult? {
        chatWaiting.incrementAndGet()
        try {
            runMutex.lock()
        } finally {
            chatWaiting.decrementAndGet()
        }
        try {
            return sendPending()
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun sendPending(): TurnResult? {
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
            writeAnchor = pending.first(),
        )
        if (!result.failed) {
            repo.markSent(pending.map { it.id })
        }
        return result
    }

    suspend fun runHourly(): TurnResult = runMutex.withLock {
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

    suspend fun runNightly(): TurnResult = runMutex.withLock {
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

    suspend fun runBriefing(): TurnResult = runMutex.withLock {
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
        writeAnchor: com.her.domain.ChatMessage? = null,
    ): TurnResult = withContext(Dispatchers.IO) {
        val runId = com.her.core.newId()
        val started = nowMillis()
        repo.saveRun(AgentRun(runId, type, started, null, 0, AgentRunStatus.RUNNING, null, null))
        val configured = llmSettings()
        if (streamToUi) _turnState.value = TurnState.Thinking
        tools.runType = type
        val receipts = mutableListOf<Receipt>()
        try {
            val buildStarted = nowMillis()
            val built = contextBuilder.build(latestUserText)
            val messages = built.messages.toMutableList()
            var anchor = writeAnchor
            val priorWrites = TurnWrites.load(anchor?.metadataJson)
            val already = if (priorWrites.isNotEmpty()) {
                "Already completed for this message, do not repeat:\n${TurnWrites.describe(priorWrites)}"
            } else {
                null
            }
            val systemExtra = listOfNotNull(extraSystem, already).joinToString("\n\n").takeIf { it.isNotBlank() }
            if (systemExtra != null) {
                messages.add(1, LlmMessage(role = "system", content = systemExtra))
            }
            repo.logDebug("prompt", "contextChars=${built.systemBundle.length} buildMs=${nowMillis() - buildStarted}")
            repo.logDebug("retrieved", built.retrieved.joinToString("\n") { "${it.memoryType}:${it.id}" })
            var lastText: String? = null
            var preamble = ""
            var silent = false
            var proactive: String? = null
            var proactivePersisted = false
            var companionAttempted = false
            var companionKey: String? = null
            val failedSame = mutableMapOf<String, Int>()
            while (budget.canCall()) {
                // A message is waiting: stop here and let it go first. A run that already has a nudge to deliver finishes.
                if (type != AgentRunType.CHAT && proactive == null && chatWaiting.get() > 0) {
                    repo.saveRun(AgentRun(runId, type, started, nowMillis(), budget.used, AgentRunStatus.CANCELLED, YIELDED, null))
                    repo.logActivity(type.name.lowercase(), "Paused so your message could go first.")
                    return@withContext TurnResult(null, true, YIELDED, budget.used, runId)
                }
                // On the last call, withhold tools so the model has to answer instead of ending silently.
                val roundTools = if (budget.isFinalCall()) emptyList() else tools.specsFor(type)
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
                repo.logDebug(
                    "usage",
                    "in=${response.usage.inputTokens} cached=${response.usage.cachedInputTokens} out=${response.usage.outputTokens} ms=${response.usage.latencyMs}",
                )
                repo.logDebug("llm", "responseChars=${response.rawJson.length}")
                val msg = response.message
                val roundText = msg.content?.trim().orEmpty().ifBlank { round.toString().trim() }
                val calls = msg.toolCalls.orEmpty()
                if (calls.isEmpty()) {
                    // A silent final answer wins over any text written before tool calls.
                    if (Identity.isSilence(roundText)) {
                        silent = true
                        break
                    }
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
                calls.forEachIndexed { index, call ->
                    val key = "${call.name}\n${call.arguments}"
                    val replayed = priorWrites.firstOrNull { it.name == call.name && it.arguments == call.arguments }
                    val result = when {
                        index >= MAX_CALLS_PER_ROUND ->
                            com.her.domain.ToolResult(call.name, false, org.json.JSONObject().put("ok", false).put("error", "Too many tool calls in one round (max $MAX_CALLS_PER_ROUND)").toString())
                        replayed != null ->
                            com.her.domain.ToolResult(call.name, true, replayed.payloadJson)
                        (failedSame[key] ?: 0) >= 2 ->
                            com.her.domain.ToolResult(call.name, false, org.json.JSONObject().put("ok", false).put("error", "This tool call already failed twice; stop retrying it.").toString())
                        else -> tools.execute(call.name, call.arguments).also { executed ->
                            if (executed.ok) {
                                priorWrites += TurnWrites.Write(call.name, call.arguments, executed.payloadJson)
                                anchor?.let { current ->
                                    val updated = current.copy(
                                        metadataJson = TurnWrites.save(current.metadataJson, priorWrites),
                                        updatedAt = nowMillis(),
                                        version = current.version + 1,
                                    )
                                    repo.saveMessage(updated, enqueueSync = false)
                                    anchor = updated
                                }
                            } else {
                                failedSame[key] = (failedSame[key] ?: 0) + 1
                            }
                        }
                    }
                    if (result.ok && replayed == null) Receipts.describe(call.name, call.arguments, result.payloadJson)?.let { receipts += it }
                    if (call.name == "send_user_message" && result.ok) {
                        val content = runCatching { org.json.JSONObject(call.arguments).optString("content").trim() }.getOrDefault("")
                        if (content.isNotBlank() && !Identity.isSilence(content)) {
                            proactive = content
                            proactivePersisted = true
                        }
                    }
                    if (call.name == "send_companion_checkin") companionAttempted = true
                    if (call.name in setOf("send_learning_reminder", "send_companion_checkin") && result.ok &&
                        org.json.JSONObject(result.payloadJson).optBoolean("queued")) {
                        if (call.name == "send_companion_checkin") companionKey = org.json.JSONObject(result.payloadJson).getString("key")
                        proactive = org.json.JSONObject(call.arguments).getString("content")
                        proactivePersisted = false
                    }
                    if (call.name !in com.her.agent.tools.companionToolNames) repo.logDebug("tool", redactSecrets("${call.name} ${call.arguments} -> ${result.payloadJson}"))
                    messages += LlmMessage(
                        role = "tool",
                        content = forModel(result.payloadJson),
                        toolCallId = call.id,
                        name = call.name,
                    )
                }
                elideToolResults(messages)
            }
            if (lastText == null && preamble.isNotBlank() && !silent) {
                lastText = preamble
            }
            val cleaned = (if (companionAttempted && type != AgentRunType.CHAT) null else lastText)?.takeIf { it.isNotBlank() && !Identity.isSilence(it) }
            if (persistAssistant && cleaned != null) {
                repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, cleaned, MessageStatus.SENT, Receipts.toMetadata(receipts)))
            }
            // A message delivered with send_user_message still needs to reach the phone.
            var notifyText = if (companionAttempted) proactive else cleaned ?: proactive
            companionKey?.let { key ->
                val notice = repo.getCompanionRecord(key)
                val topic = notice?.let { repo.getCompanionRecord(it.parentId) }
                if (notice == null || notice.deletedAt != null || topic == null ||
                    com.her.companion.checkinBlock(repo, settings, topic, notice.data().optString("phase"), key) != null) notifyText = null
            }
            val deliveryText = notifyText
            if (allowNotify && deliveryText != null) {
                val alreadySaid = type in setOf(AgentRunType.HOURLY, AgentRunType.CATCH_UP) &&
                    alreadySaidProactively(repo.recentMessages(80), deliveryText, nowMillis(), started)
                val decision = if (alreadySaid) {
                    NotifyDecision(false, "already said", companionKey ?: "$type:${deliveryText.take(40)}")
                } else {
                    policy.shouldNotify(
                        key = companionKey ?: "$type:${deliveryText.take(40)}",
                        urgent = type == AgentRunType.BRIEFING,
                        now = repo.now(),
                    )
                }
                if (decision.notify) {
                    // Opening the app from the notification has to show the same words, so an autonomous
                    // reply that was not already saved (briefing, send_user_message) is saved first.
                    if ((!persistAssistant || cleaned == null) && (deliveryText != proactive || !proactivePersisted)) {
                        repo.saveMessage(repo.newChatMessage(MessageRole.ASSISTANT, deliveryText, MessageStatus.SENT, """{"proactive":true}"""))
                    }
                    notifier.companionNoticeKey = companionKey
                    notifier.show(deliveryText, type == AgentRunType.BRIEFING)
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
                val language = runCatching { repo.getProfile().preferredLanguage }.getOrNull()
                repo.saveMessage(
                    repo.newChatMessage(MessageRole.ASSISTANT, failureReply(language), MessageStatus.FAILED, Receipts.toMetadata(receipts)),
                )
            }
            TurnResult(null, true, error, budget.used, runId)
        } finally {
            tools.runType = AgentRunType.CHAT
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
        const val MAX_CALLS_PER_ROUND = 8
        const val MAX_TURN_CHARS = 80_000
        const val YIELDED = "yielded to chat"

        /** Rate limits, server errors, truncated streams, and malformed bodies are worth another try. */
        internal fun isRetryable(e: IOException): Boolean = when (e) {
            is LlmException -> {
                val status = e.status
                val msg = e.message.orEmpty()
                status == 429 || (status != null && status >= 500) ||
                    (status == null && (msg.contains("malformed", ignoreCase = true) ||
                        msg.contains("no choices", ignoreCase = true) ||
                        msg.contains("truncated", ignoreCase = true)))
            }
            else -> true
        }

        internal fun elideToolResults(messages: MutableList<LlmMessage>, maxChars: Int = MAX_TURN_CHARS) {
            fun total(): Int = messages.sumOf { (it.content?.length ?: 0) }
            if (total() <= maxChars) return
            for (i in messages.indices) {
                val message = messages[i]
                if (message.role == "tool" && (message.content?.length ?: 0) > 40) {
                    messages[i] = message.copy(content = """{"ok":true,"elided":true}""")
                }
                if (total() <= maxChars) return
            }
        }

        internal fun failureReply(preferredLanguage: String?): String {
            val language = preferredLanguage?.trim()?.lowercase().orEmpty()
            val persian = language == "fa" || "persian" in language || "farsi" in language || "فارسی" in language
            return if (persian) {
                "الان نتوانستم به مدل وصل شوم. پیامت ذخیره شده — کمی بعد دوباره امتحان کن."
            } else {
                "I couldn't reach the model just now. Your message is saved — try again in a moment."
            }
        }

        internal fun forModel(payload: String): String =
            if (payload.length <= MAX_TOOL_PAYLOAD_CHARS) {
                payload
            } else {
                payload.take(MAX_TOOL_PAYLOAD_CHARS) + "\n…[truncated ${payload.length - MAX_TOOL_PAYLOAD_CHARS} chars]"
            }
    }
}
