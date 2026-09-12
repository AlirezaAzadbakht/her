package com.her.agent.prompt

import com.her.core.formatNaturalDate
import com.her.data.calendar.CalendarDataSource
import com.her.data.repository.HerRepository
import com.her.data.retrieval.MemoryRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.LlmMessage
import com.her.domain.MemoryHit
import java.time.Instant
import java.time.ZoneId

data class BuiltContext(
    val messages: List<LlmMessage>,
    val retrieved: List<MemoryHit>,
    val systemBundle: String,
)

class ContextBuilder(
    private val repo: HerRepository,
    private val ranker: MemoryRanker,
    private val settings: AppSettingsStore,
    private val calendar: CalendarDataSource,
    private val extraSystem: String? = null,
) {
    suspend fun build(latestUserText: String? = null, recentLimit: Int = 24): BuiltContext {
        val profile = repo.getProfile()
        val zone = runCatching { ZoneId.of(profile.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
        val recent = repo.recentMessages(recentLimit)
        val query = latestUserText ?: recent.takeLast(3).joinToString(" ") { it.content }
        val relatedIds = repo.relationships().flatMap { listOf(it.sourceId, it.targetId) }.toSet()
        val memories = ranker.search(query, limit = 12, relatedIds = relatedIds)
        val people = repo.people().take(12)
        val projects = repo.projects().take(10)
        val goals = repo.goals().filter { it.status.name == "ACTIVE" }.take(10)
        val tasks = repo.tasks().filter { it.status.name == "OPEN" }.take(12)
        val commitments = repo.commitments().filter { it.status.name == "OPEN" }.take(12)
        val loops = repo.openLoops().filter { it.status.name == "OPEN" }.take(8)
        val routines = repo.routines().take(8)
        val groceries = repo.groceries().filter { it.status.name == "ACTIVE" }.take(20)
        val dates = repo.importantDates().take(12)
        val queue = repo.agentQueue().filter { it.status.name == "OPEN" }.take(12)
        val state = repo.agentState().take(12)
        val from = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val to = now.toLocalDate().plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val internalCal = repo.calendarInRange(from, to)
        val systemCal = if (settings.read().calendarEnabled && calendar.hasPermission()) {
            calendar.eventsBetween(from, to).getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val bundle = buildString {
            appendLine("Current local time: ${formatNaturalDate(now)} (${zone.id})")
            appendLine("User profile:")
            appendLine("- name: ${profile.userName ?: "unknown"}")
            appendLine("- they call you: ${profile.assistantName ?: "Her"}")
            appendLine("- timezone: ${profile.timezone ?: zone.id}")
            appendLine("- language: ${profile.preferredLanguage ?: "unspecified"}")
            appendLine("- work/study: ${profile.occupationOrStudyContext ?: "unspecified"}")
            appendLine("- wake/sleep: ${profile.typicalWakeTime ?: "?"} / ${profile.typicalSleepTime ?: "?"}")
            appendSection("Retrieved memories", memories.map { "[${it.memoryType} ${"%.2f".format(it.score)}] ${it.content}" })
            appendSection("People", people.map { "${it.name} (${it.relationship ?: "?"}) bday=${it.birthday ?: "-"} ${it.importantNotes ?: ""}" })
            appendSection("Projects", projects.map { "${it.name}: ${it.summary ?: it.description ?: it.status}" })
            appendSection("Goals", goals.map { "${it.title} by ${it.targetDate ?: "unspecified"} — ${it.progressSummary ?: ""}" })
            appendSection("Tasks", tasks.map { it.title })
            appendSection("Commitments", commitments.map { "${it.title}${it.dueAt?.let { d -> " due $d" } ?: ""}" })
            appendSection("Open loops", loops.map { it.description })
            appendSection("Routines", routines.map { "${it.title} (${it.schedule ?: "?"}, c=${it.confidence})" })
            appendSection("Groceries", groceries.map { listOfNotNull(it.name, it.quantity, it.reason).joinToString(" ") })
            appendSection("Important dates", dates.map { "${it.dateIso} ${it.title}" })
            appendSection("Internal calendar", internalCal.map { "${it.startAt} ${it.title}" })
            if (systemCal.isNotEmpty()) appendSection("System calendar", systemCal.map { "${it.startAt} ${it.title}" })
            appendSection("Agent state", state.map { "${it.kind}: ${it.content}" })
            appendSection("Agent queue", queue.map { it.description })
            extraSystem?.let {
                appendLine()
                appendLine(it)
            }
        }

        val messages = buildList {
            add(LlmMessage(role = "system", content = Identity.SYSTEM_PROMPT))
            add(LlmMessage(role = "system", content = bundle))
            recent.forEach { msg ->
                add(LlmMessage(role = msg.role.name.lowercase(), content = msg.content))
            }
        }
        return BuiltContext(messages, memories, bundle)
    }

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("$title:")
        lines.take(16).forEach { appendLine("- $it") }
    }
}
