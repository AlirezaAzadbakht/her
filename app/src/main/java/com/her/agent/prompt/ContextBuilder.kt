package com.her.agent.prompt

import com.her.core.formatNaturalDate
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.GoogleCalendar
import com.her.data.calendar.GoogleCalendarClient
import com.her.data.calendar.SystemCalendar
import com.her.domain.CalendarSource
import com.her.data.repository.HerRepository
import com.her.data.retrieval.MemoryRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.LlmMessage
import com.her.domain.MemoryHit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class BuiltContext(
    val messages: List<LlmMessage>,
    val retrieved: List<MemoryHit>,
    val systemBundle: String,
)

private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

class ContextBuilder(
    private val repo: HerRepository,
    private val ranker: MemoryRanker,
    private val settings: AppSettingsStore,
    private val calendar: CalendarDataSource,
    private val extraSystem: String? = null,
    private val systemCalendar: SystemCalendar = SystemCalendar(repo, calendar, settings),
    private val googleCalendar: GoogleCalendar = GoogleCalendar(repo, GoogleCalendarClient()),
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
        val googleLive = googleCalendar.available()
        val systemCal = systemCalendar.mirror(from, to, excludeGoogleAccounts = googleLive)
        val googleCal = googleCalendar.mirror(from, to)
        val internalCal = repo.calendarInRange(from, to).filter { it.source == CalendarSource.INTERNAL }

        val bundle = buildString {
            appendLine("Current local time: ${formatNaturalDate(now)} (${zone.id})")
            appendLine("User profile:")
            appendLine("- name: ${profile.userName ?: "unknown"}")
            appendLine("- they call you: ${profile.assistantName ?: "Her"}")
            appendLine("- timezone: ${profile.timezone ?: zone.id}")
            appendLine("- language: ${profile.preferredLanguage ?: "unspecified"}")
            appendLine("- work/study: ${profile.occupationOrStudyContext ?: "unspecified"}")
            appendLine("- wake/sleep: ${profile.typicalWakeTime ?: "?"} / ${profile.typicalSleepTime ?: "?"}")
            appendSection("Retrieved memories", memories.map { "${it.id} [${it.memoryType} ${"%.2f".format(it.score)}] ${it.content}" })
            appendSection("People", people.map { "${it.id} | ${it.name} (${it.relationship ?: "?"}) bday=${it.birthday ?: "-"} ${it.importantNotes ?: ""}" })
            appendSection("Projects", projects.map { "${it.id} | ${it.name}: ${it.summary ?: it.description ?: it.status}" })
            appendSection("Goals", goals.map { "${it.id} | ${it.title} [${it.status}] by ${it.targetDate ?: "unspecified"} — ${it.progressSummary ?: ""}" })
            appendSection("Tasks", tasks.map { "${it.id} | ${it.title} [${it.status}]${due(it.dueAt, now, zone)}" })
            appendSection("Commitments", commitments.map { "${it.id} | ${it.title} [${it.status}]${due(it.dueAt, now, zone)}" })
            appendSection("Open loops", loops.map { "${it.id} | ${it.description} [${it.status}]" })
            appendSection("Routines", routines.map { "${it.id} | ${it.title} (${it.schedule ?: "?"}, c=${it.confidence})" })
            appendSection("Groceries", groceries.map { "${it.id} | ${listOfNotNull(it.name, it.quantity, it.reason).joinToString(" ")} [${it.status}]" })
            appendSection("Important dates", dates.map { "${it.id} | ${it.dateIso} ${it.title}" })
            appendSection("Internal calendar", internalCal.map { "${it.id} | ${stamp(it.startAt, zone)}–${clock(it.endAt, zone)} ${it.title}" })
            appendCalendarSection(
                "System calendar",
                systemCal.map { "${it.id} | ${stamp(it.startAt, zone)}–${clock(it.endAt, zone)} ${it.title}" },
                live = systemCalendar.live(),
            )
            appendCalendarSection(
                "Google calendar",
                googleCal.map { "${it.id} | ${stamp(it.startAt, zone)}–${clock(it.endAt, zone)} ${it.title}" },
                live = googleLive,
            )
            appendSection("Agent state", state.map { "${it.id} | ${it.kind}: ${it.content}" })
            appendSection("Agent queue", queue.map { "${it.id} | ${it.description} [${it.status}]${due(it.dueAt, now, zone)}" })
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

    private fun stamp(millis: Long, zone: ZoneId): String =
        formatNaturalDate(Instant.ofEpochMilli(millis).atZone(zone))

    private fun clock(millis: Long?, zone: ZoneId): String =
        millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(clockFormat) } ?: "?"

    private fun due(millis: Long?, now: ZonedDateTime, zone: ZoneId): String {
        if (millis == null) return ""
        val when_ = Instant.ofEpochMilli(millis).atZone(zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), when_.toLocalDate())
        val marker = when {
            when_.isBefore(now) -> " OVERDUE"
            days == 0L -> " due today"
            days == 1L -> " due tomorrow"
            days <= 3L -> " due in $days days"
            else -> ""
        }
        return " due ${stamp(millis, zone)}$marker"
    }

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("$title:")
        lines.take(16).forEach { appendLine("- $it") }
    }

    private fun StringBuilder.appendCalendarSection(title: String, lines: List<String>, live: Boolean) {
        if (!live) return
        appendLine()
        appendLine("$title:")
        if (lines.isEmpty()) {
            appendLine("- none in the next 7 days")
        } else {
            lines.take(16).forEach { appendLine("- $it") }
        }
    }
}
