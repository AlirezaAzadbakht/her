package com.her.scenario

import com.her.core.RelativeTimeParser
import com.her.core.normalizeDigits
import com.her.data.repository.HerRepository
import com.her.domain.ChatMessage
import com.her.domain.MessageRole
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

data class CheckResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

data class RecordedToolCall(
    val name: String,
    val arguments: String,
    val ok: Boolean,
    val payloadJson: String,
)

object Checks {
    suspend fun evaluate(
        spec: ScenarioSpec,
        repo: HerRepository,
        outcome: HarnessOutcome,
        extraTables: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ): List<CheckResult> {
        val expect = spec.expect
        val results = mutableListOf<CheckResult>()
        val toolCalls = outcome.toolCalls
        val lastReply = outcome.lastReply
        val called = toolCalls.map { it.name }
        val zone = profileZone(repo)
        val now = Instant.ofEpochMilli(repo.clock.nowMillis()).atZone(zone)
        expect.toolsCalled.forEach { name ->
            val hit = called.contains(name)
            results += CheckResult(
                name = "tools_called:$name",
                passed = hit,
                detail = if (hit) "called" else "not called; saw ${called.ifEmpty { listOf("(none)") }.joinToString()}",
            )
        }
        expect.toolsNotCalled.forEach { name ->
            val hit = called.contains(name)
            results += CheckResult(
                name = "tools_not_called:$name",
                passed = !hit,
                detail = if (hit) "was called" else "not called",
            )
        }
        if (expect.noToolErrors) {
            val failed = toolCalls.filter { call ->
                !call.ok && !JSONObject(call.payloadJson).optBoolean("needsConfirmation", false)
            }
            results += CheckResult(
                name = "no_tool_errors",
                passed = failed.isEmpty(),
                detail = if (failed.isEmpty()) {
                    "all ${toolCalls.size} tool calls succeeded"
                } else {
                    failed.joinToString { "${it.name}: ${it.payloadJson}" }
                },
            )
        }
        expect.toolCallArgs.forEachIndexed { index, wanted ->
            val sameName = toolCalls.filter { it.name == wanted.name }
            val hit = sameName.any { matchesRow(argumentFields(it.arguments), wanted.args, now) }
            results += CheckResult(
                name = "tools_called[$index]:${wanted.name}(args)",
                passed = hit,
                detail = when {
                    hit -> "a ${wanted.name} call matched the args"
                    sameName.isEmpty() -> "not called; saw ${called.ifEmpty { listOf("(none)") }.joinToString()}"
                    else -> "no ${wanted.name} call matched; args were ${sameName.joinToString(" | ") { it.arguments }}"
                },
            )
        }
        if (expect.toolOrder.isNotEmpty()) {
            val inOrder = isSubsequence(expect.toolOrder, called)
            results += CheckResult(
                name = "tool_order",
                passed = inOrder,
                detail = "wanted ${expect.toolOrder.joinToString(" → ")} in order; saw ${called.ifEmpty { listOf("(none)") }.joinToString(" → ")}",
            )
        }
        expect.maxToolCalls?.let { max ->
            results += CheckResult("max_tool_calls", toolCalls.size <= max, "${toolCalls.size} tool calls (max $max)")
        }
        expect.maxLlmCalls?.let { max ->
            results += CheckResult("max_llm_calls", outcome.llmCalls <= max, "${outcome.llmCalls} LLM calls (max $max)")
        }
        expect.maxInputTokens?.let { max ->
            results += CheckResult("max_input_tokens", outcome.inputTokens <= max, "${outcome.inputTokens} input tokens (max $max)")
        }
        expect.notifications?.let { wanted ->
            val texts = outcome.notifications.map { it.text }
            wanted.count?.let { count ->
                results += CheckResult(
                    name = "notifications.count",
                    passed = texts.size == count,
                    detail = "${texts.size} notification(s) (wanted $count)${if (texts.isEmpty()) "" else ": ${texts.joinToString(" | ")}"}",
                )
            }
            if (wanted.containsAny.isNotEmpty()) {
                val hit = texts.any { text -> wanted.containsAny.any { needle -> normalizeDigits(text).contains(normalizeDigits(needle), ignoreCase = true) } }
                results += CheckResult(
                    name = "notifications.contains_any",
                    passed = hit,
                    detail = if (hit) "a notification mentioned one of ${wanted.containsAny}" else "none mentioned ${wanted.containsAny}: ${texts.ifEmpty { listOf("(none)") }.joinToString(" | ")}",
                )
            }
        }
        expect.rows.forEachIndexed { index, row ->
            val rows = tableRows(repo, row.table, extraTables)
            val matched = rows.filter { matchesRow(it, row.where, now) }
            val passed = if (row.count == null) matched.isNotEmpty() else matched.size == row.count
            val wanted = row.count?.let { "count=$it" } ?: "at least 1"
            results += CheckResult(
                name = "rows[$index]:${row.table}",
                passed = passed,
                detail = "$wanted matching row(s); matched ${matched.size} of ${rows.size}. ${summarize(matched)}",
            )
        }
        val reply = expect.reply
        if (reply != null && reply.mustMentionAny.isNotEmpty()) {
            val text = normalizeDigits(lastReply.orEmpty())
            val hit = reply.mustMentionAny.any { text.contains(normalizeDigits(it), ignoreCase = true) }
            results += CheckResult(
                name = "reply.must_mention_any",
                passed = hit,
                detail = if (hit) {
                    "reply mentioned one of ${reply.mustMentionAny}"
                } else {
                    "reply did not mention any of ${reply.mustMentionAny}: ${text.ifBlank { "(empty)" }}"
                },
            )
        }
        if (reply != null && reply.mustNotMention.isNotEmpty()) {
            val text = normalizeDigits(lastReply.orEmpty())
            val found = reply.mustNotMention.filter { text.contains(normalizeDigits(it), ignoreCase = true) }
            results += CheckResult(
                name = "reply.must_not_mention",
                passed = found.isEmpty(),
                detail = if (found.isEmpty()) "reply avoided ${reply.mustNotMention}" else "reply mentioned $found: $text",
            )
        }
        reply?.language?.let { wanted ->
            val actual = detectLanguage(lastReply.orEmpty())
            results += CheckResult(
                name = "reply.language",
                passed = actual == wanted,
                detail = "wanted $wanted; reply reads as ${actual ?: "(no letters)"}",
            )
        }
        return results
    }

    /** Persian when most letters are Arabic-script, English otherwise. */
    fun detectLanguage(text: String): String? {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val arabicScript = letters.count { it in '؀'..'ۿ' || it in 'ﭐ'..'﻿' }
        return if (arabicScript * 2 > letters.length) "persian" else "english"
    }

    internal fun isSubsequence(wanted: List<String>, seen: List<String>): Boolean {
        var next = 0
        seen.forEach { name -> if (next < wanted.size && wanted[next] == name) next += 1 }
        return next == wanted.size
    }

    internal fun argumentFields(arguments: String): Map<String, Any?> {
        val obj = runCatching { JSONObject(arguments) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence().associateWith { key -> obj.opt(key).takeUnless { it == JSONObject.NULL } }
    }

    suspend fun dumpTables(
        repo: HerRepository,
        extraTables: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ): String = buildString {
        (ScenarioTables.ALL + extraTables.keys).sorted().forEach { table ->
            val rows = tableRows(repo, table, extraTables)
            appendLine("$table (${rows.size})")
            rows.take(20).forEach { row ->
                appendLine("  " + row.entries.joinToString { "${it.key}=${it.value}" })
            }
        }
    }

    suspend fun tableRows(
        repo: HerRepository,
        table: String,
        extraTables: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ): List<Map<String, Any?>> {
        extraTables[table]?.let { return it }
        val now = repo.clock.nowMillis()
        val from = now - 30L * 24 * 60 * 60 * 1000
        val to = now + 400L * 24 * 60 * 60 * 1000
        return when (table) {
            "chat_messages" -> repo.recentMessages(200).map { messageFields(it) }
            "people" -> repo.people().map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "relationship" to it.relationship,
                    "birthday" to it.birthday,
                    "importantNotes" to it.importantNotes,
                    "preferences" to it.preferences,
                    "confidence" to it.confidence,
                )
            }
            "projects" -> repo.projects().map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "description" to it.description,
                    "status" to it.status.name,
                    "summary" to it.summary,
                    "importance" to it.importance,
                )
            }
            "goals" -> repo.goals().map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "description" to it.description,
                    "status" to it.status.name,
                    "priority" to it.priority,
                    "targetDate" to it.targetDate,
                    "progressSummary" to it.progressSummary,
                )
            }
            "tasks" -> repo.tasks().map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "description" to it.description,
                    "status" to it.status.name,
                    "dueAt" to it.dueAt,
                )
            }
            "commitments" -> repo.commitments().map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "description" to it.description,
                    "status" to it.status.name,
                    "dueAt" to it.dueAt,
                    "promisedTo" to it.promisedTo,
                )
            }
            "open_loops" -> repo.openLoops().map {
                mapOf(
                    "id" to it.id,
                    "description" to it.description,
                    "status" to it.status.name,
                    "importance" to it.importance,
                    "confidence" to it.confidence,
                )
            }
            "routines" -> repo.routines().map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "description" to it.description,
                    "schedule" to it.schedule,
                    "confidence" to it.confidence,
                )
            }
            "groceries" -> repo.groceries().map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "quantity" to it.quantity,
                    "category" to it.category,
                    "status" to it.status.name,
                    "reason" to it.reason,
                    "notes" to it.notes,
                    "store" to it.store,
                )
            }
            "important_dates" -> repo.importantDates().map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "dateIso" to it.dateIso,
                    "recurrence" to it.recurrence,
                    "relatedPersonId" to it.relatedPersonId,
                    "notes" to it.notes,
                    "importance" to it.importance,
                )
            }
            "recurring_responsibilities" -> repo.responsibilities().map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "cadence" to it.cadence,
                    "nextDueAt" to it.nextDueAt,
                    "notes" to it.notes,
                )
            }
            "calendar_events" -> repo.calendarInRange(from, to).map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "startAt" to it.startAt,
                    "endAt" to it.endAt,
                    "location" to it.location,
                    "notes" to it.notes,
                    "source" to it.source.name,
                )
            }
            "agent_queue" -> repo.agentQueue().map {
                mapOf(
                    "id" to it.id,
                    "description" to it.description,
                    "status" to it.status.name,
                    "priority" to it.priority,
                    "dueAt" to it.dueAt,
                )
            }
            "agent_state" -> repo.agentState().map {
                mapOf(
                    "id" to it.id,
                    "kind" to it.kind,
                    "content" to it.content,
                    "confidence" to it.confidence,
                )
            }
            "memories_long" -> repo.activeLong().map {
                mapOf(
                    "id" to it.id,
                    "content" to it.content,
                    "category" to it.category,
                    "confidence" to it.confidence,
                    "importance" to it.importance,
                    "status" to it.status.name,
                    "source" to it.source.name,
                )
            }
            "memories_short" -> repo.activeShort().map {
                mapOf(
                    "id" to it.id,
                    "content" to it.content,
                    "type" to it.type,
                    "confidence" to it.confidence,
                    "importance" to it.importance,
                    "source" to it.source.name,
                )
            }
            "user_understandings" -> repo.activeUserUnderstandings().map {
                mapOf(
                    "id" to it.id,
                    "facet" to it.facet,
                    "content" to it.content,
                    "confidence" to it.confidence,
                    "importance" to it.importance,
                    "status" to it.status.name,
                    "source" to it.source.name,
                )
            }
            "reminders" -> repo.reminders().map {
                mapOf(
                    "id" to it.id,
                    "message" to it.message,
                    "trigger" to it.trigger.name,
                    "fireAt" to it.fireAt,
                    "repeat" to it.repeat.name,
                    "person" to it.personId?.let { id -> repo.getPerson(id)?.name },
                    "place" to it.place,
                    "onlyIfEntityType" to it.onlyIfEntityType,
                    "onlyIfEntityId" to it.onlyIfEntityId,
                    "status" to it.status.name,
                )
            }
            "profile" -> repo.getProfile().let {
                listOf(
                    mapOf(
                        "userName" to it.userName,
                        "assistantName" to it.assistantName,
                        "timezone" to it.timezone,
                        "preferredLanguage" to it.preferredLanguage,
                        "country" to it.country,
                        "typicalWakeTime" to it.typicalWakeTime,
                        "typicalSleepTime" to it.typicalSleepTime,
                        "occupationOrStudyContext" to it.occupationOrStudyContext,
                    ),
                )
            }
            else -> error("Unknown table: $table")
        }
    }

    fun lastAssistantReply(messages: List<ChatMessage>, fallback: String?): String? =
        messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }

    internal fun matchesRow(
        row: Map<String, Any?>,
        where: Map<String, List<FieldMatcher>>,
        now: ZonedDateTime,
    ): Boolean = where.all { (field, matchers) ->
        matchers.all { matcher -> matches(row[field], matcher, now) }
    }

    internal fun matches(value: Any?, matcher: FieldMatcher, now: ZonedDateTime): Boolean {
        val text = stringify(value)
        return when (matcher) {
            is FieldMatcher.Equals -> valuesEqual(value, matcher.value)
            is FieldMatcher.EqualsIgnoreCase -> text.equals(matcher.value, ignoreCase = true)
            is FieldMatcher.Contains -> text.contains(matcher.value, ignoreCase = true)
            is FieldMatcher.ContainsAny -> matcher.values.any { text.contains(it, ignoreCase = true) }
            is FieldMatcher.ContainsAll -> matcher.values.all { text.contains(it, ignoreCase = true) }
            is FieldMatcher.Matches -> Regex(matcher.pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
            is FieldMatcher.OneOf -> matcher.values.any { text.equals(it, ignoreCase = true) }
            FieldMatcher.NotEmpty -> text.isNotBlank()
            is FieldMatcher.GreaterThan -> asNumber(value)?.let { it > matcher.value } == true
            is FieldMatcher.GreaterOrEqual -> asNumber(value)?.let { it >= matcher.value } == true
            is FieldMatcher.LessThan -> asNumber(value)?.let { it < matcher.value } == true
            is FieldMatcher.LessOrEqual -> asNumber(value)?.let { it <= matcher.value } == true
            is FieldMatcher.DateIs -> datesEqual(value, matcher.phrase, now)
            is FieldMatcher.TimeIs -> timesEqual(value, matcher.phrase, now)
            is FieldMatcher.WithinDays -> withinDays(value, matcher.days, now)
        }
    }

    private fun valuesEqual(actual: Any?, expected: Any): Boolean {
        val left = asNumber(actual)
        val right = asNumber(expected)
        if (left != null && right != null) return left == right
        return stringify(actual) == stringify(expected)
    }

    private fun datesEqual(value: Any?, phrase: String, now: ZonedDateTime): Boolean {
        val actual = asLocalDate(value, now.zone) ?: return false
        val expected = parseDatePhrase(phrase, now) ?: return false
        return actual == expected
    }

    private fun timesEqual(value: Any?, phrase: String, now: ZonedDateTime): Boolean {
        val actual = asLocalTime(value, now.zone) ?: return false
        val expected = parseTimePhrase(phrase) ?: return false
        return actual.hour == expected.hour && actual.minute == expected.minute
    }

    private fun withinDays(value: Any?, days: Int, now: ZonedDateTime): Boolean {
        val actual = asLocalDate(value, now.zone) ?: return false
        val today = now.toLocalDate()
        return !actual.isBefore(today) && !actual.isAfter(today.plusDays(days.toLong()))
    }

    fun parseDatePhrase(phrase: String, now: ZonedDateTime): LocalDate? {
        RelativeTimeParser.parse(phrase, now)?.let { return it.toLocalDate() }
        return runCatching { LocalDate.parse(phrase.trim()) }.getOrNull()
    }

    fun parseTimePhrase(phrase: String): LocalTime? {
        val raw = phrase.trim()
        val patterns = listOf(
            DateTimeFormatter.ofPattern("H:mm", Locale.US),
            DateTimeFormatter.ofPattern("HH:mm", Locale.US),
            DateTimeFormatter.ofPattern("H:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("h a", Locale.US),
        )
        patterns.forEach { formatter ->
            runCatching { return LocalTime.parse(raw.uppercase(Locale.US), formatter) }
        }
        val match = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""", RegexOption.IGNORE_CASE)
            .matchEntire(raw)
            ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val meridiem = match.groupValues[3].lowercase(Locale.US)
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun asLocalDate(value: Any?, zone: ZoneId): LocalDate? {
        asNumber(value)?.toLong()?.let { n ->
            if (n > 10_000_000_000L) return Instant.ofEpochMilli(n).atZone(zone).toLocalDate()
            if (n > 1_000_000_000L) return Instant.ofEpochSecond(n).atZone(zone).toLocalDate()
        }
        val text = stringify(value)
        if (text.isBlank()) return null
        text.toLongOrNull()?.let { n ->
            return if (n > 10_000_000_000L) {
                Instant.ofEpochMilli(n).atZone(zone).toLocalDate()
            } else if (n > 1_000_000_000L) {
                Instant.ofEpochSecond(n).atZone(zone).toLocalDate()
            } else {
                null
            }
        }
        return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }

    private fun asLocalTime(value: Any?, zone: ZoneId): LocalTime? {
        asNumber(value)?.toLong()?.let { n ->
            if (n > 10_000_000_000L) return Instant.ofEpochMilli(n).atZone(zone).toLocalTime()
            if (n > 1_000_000_000L) return Instant.ofEpochSecond(n).atZone(zone).toLocalTime()
        }
        val text = stringify(value)
        if (text.isBlank()) return null
        text.toLongOrNull()?.let { n ->
            return if (n > 10_000_000_000L) {
                Instant.ofEpochMilli(n).atZone(zone).toLocalTime()
            } else {
                null
            }
        }
        return parseTimePhrase(text)
    }

    private fun asNumber(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> stringify(value).toDoubleOrNull()
    }

    private fun stringify(value: Any?): String = when (value) {
        null -> ""
        is Enum<*> -> value.name
        else -> value.toString()
    }

    private fun messageFields(message: ChatMessage): Map<String, Any?> = mapOf(
        "id" to message.id,
        "role" to message.role.name,
        "content" to message.content,
        "status" to message.status.name,
        "metadataJson" to message.metadataJson,
    )

    private suspend fun profileZone(repo: HerRepository): ZoneId {
        val tz = repo.getProfile().timezone
        return runCatching { ZoneId.of(tz ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
    }

    private fun summarize(rows: List<Map<String, Any?>>): String {
        if (rows.isEmpty()) return "matches=[]"
        return rows.take(3).joinToString(prefix = "matches=", separator = " | ") { row ->
            row.entries.joinToString { "${it.key}=${it.value}" }
        }
    }
}
