package com.her.agent.prompt

import com.her.core.formatNaturalDate
import com.her.data.calendar.CalendarDataSource
import com.her.data.calendar.GoogleCalendar
import com.her.data.calendar.GoogleCalendarClient
import com.her.data.calendar.SystemCalendar
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import com.her.domain.Reminder
import com.her.domain.ReminderRepeat
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import com.her.data.repository.HerRepository
import com.her.data.retrieval.MemoryRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.LlmMessage
import com.her.domain.MemoryHit
import com.her.work.BackgroundRunPolicy
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
private const val DAY_MS = 24 * 60 * 60 * 1000L

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
        val zone = repo.profileZone()
        val now = Instant.ofEpochMilli(repo.clock.nowMillis()).atZone(zone)
        val recent = repo.recentMessages(recentLimit)
        val query = latestUserText ?: recent.takeLast(3).joinToString(" ") { it.content }
        val relatedIds = repo.relationships().flatMap { listOf(it.sourceId, it.targetId) }.toSet()
        val memories = ranker.search(query, limit = 12, relatedIds = relatedIds)
        val understandings = repo.activeUserUnderstandings().take(16)
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
        val allState = repo.agentState()
        val digest = allState.firstOrNull { it.kind == Identity.DIGEST_KIND }
        val state = allState.filter { it.kind != Identity.DIGEST_KIND }.take(12)
        val week = week(now, zone)
        val (internalCal, systemCal, googleCal) = week
        val googleLive = week.googleLive
        val nowMs = now.toInstant().toEpochMilli()
        val nextDay = week.all.filter { it.startAt in nowMs..(nowMs + DAY_MS) }
        val reminders = repo.reminders().filter { it.status == ReminderStatus.SCHEDULED }.take(12)
            .map { reminderLine(it, nextDay, zone) }

        // Slow-changing sections come first so providers can reuse the cached prompt prefix across calls.
        // The clock, retrieval, and live lists change every turn, so they come after.
        val bundle = buildString {
            appendLine("User profile:")
            appendLine("- name: ${profile.userName ?: "unknown"}")
            appendLine("- they call you: ${profile.assistantName ?: "Her"}")
            appendLine("- timezone: ${profile.timezone ?: zone.id}")
            appendLine("- language: ${profile.preferredLanguage ?: "unspecified"}")
            appendLine("- work/study: ${profile.occupationOrStudyContext ?: "unspecified"}")
            appendLine("- wake/sleep: ${profile.typicalWakeTime ?: "?"} / ${profile.typicalSleepTime ?: "?"}")
            appendLine()
            appendLine("About them:")
            if (understandings.isEmpty()) {
                appendLine("- none yet")
            } else {
                understandings.forEach {
                    appendLine("- ${it.id} | ${it.facet} | ${it.content} (c=${"%.2f".format(it.confidence)})")
                }
            }
            appendLine("Web search: ${if (settings.read().webSearchEnabled) "on" else "off"}")
            appendSection("People", people.map { "${it.id} | ${it.name} (${it.relationship ?: "?"}) bday=${it.birthday ?: "-"} ${it.importantNotes ?: ""}" })
            appendSection("Projects", projects.map { "${it.id} | ${it.name}: ${it.summary ?: it.description ?: it.status}" })
            appendSection("Goals", goals.map { "${it.id} | ${it.title} [${it.status}] by ${it.targetDate ?: "unspecified"} — ${it.progressSummary ?: ""}" })
            appendSection("Routines", routines.map { "${it.id} | ${it.title} (${it.schedule ?: "?"}, c=${it.confidence})" })
            appendSection("Important dates", dates.map { "${it.id} | ${it.dateIso} ${it.title}" })
            digest?.let { appendSection("Recent days (your notes)", listOf("${it.id} | ${it.content}")) }
            appendLine()
            appendLine("Current local time: ${formatNaturalDate(now)} (${zone.id})")
            recent.lastOrNull { it.role.name == "USER" }?.let {
                appendLine("Their last message was sent: ${stamp(it.createdAt, zone)}")
            }
            settings.read().swipedNudges.takeIf { it >= 2 }?.let {
                appendLine("Their last $it notifications were swiped away without a reply.")
            }
            appendSection("Retrieved memories", memories.map { "${it.id} [${it.memoryType} ${"%.2f".format(it.score)}] ${it.content}" })
            appendSection("Tasks", tasks.map { "${it.id} | ${it.title} [${it.status}]${due(it.dueAt, now, zone)}" })
            appendSection("Commitments", commitments.map { "${it.id} | ${it.title} [${it.status}]${due(it.dueAt, now, zone)}" })
            appendSection("Reminders", reminders)
            appendSection("Open loops", loops.map { "${it.id} | ${it.description} [${it.status}]" })
            appendSection("Groceries", groceries.map { "${it.id} | ${listOfNotNull(it.name, it.quantity, it.reason).joinToString(" ")} [${it.status}]" })
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

    /**
     * What an autonomous pass could speak about now: open deadlines due today or overdue, events starting within
     * [horizonMs], dates today or tomorrow, a "next time with" person on the calendar, and with [agentItems] the
     * queue items that came due and their latest message. Keys carry their time, so a moved deadline is new again.
     */
    suspend fun triggers(horizonMs: Long, agentItems: Boolean = true): Set<String> {
        val zone = repo.profileZone()
        val now = Instant.ofEpochMilli(repo.clock.nowMillis()).atZone(zone)
        val nowMs = now.toInstant().toEpochMilli()
        val events = week(now, zone).all
        val nextDay = events.filter { it.startAt in nowMs..(nowMs + DAY_MS) }
        val deadlines = repo.tasks().filter { it.status.name == "OPEN" }.map { it.id to it.dueAt } +
            repo.commitments().filter { it.status.name == "OPEN" }.map { it.id to it.dueAt }
        return buildSet {
            deadlines.forEach { (id, dueAt) ->
                dueAt?.let { BackgroundRunPolicy.deadlineState(it, now) }?.let { add("due:$id:$dueAt:$it") }
            }
            events.filter { it.startAt in nowMs..(nowMs + horizonMs) }.forEach { add("event:${it.startAt}:${it.title}") }
            repo.importantDates().forEach { date ->
                BackgroundRunPolicy.upcomingOccurrence(date.dateIso, now.toLocalDate())?.let { add("date:${date.id}:$it") }
            }
            repo.reminders().filter { it.status == ReminderStatus.SCHEDULED && it.trigger == ReminderTrigger.PERSON }.forEach {
                meeting(personName(it), nextDay)?.let { event -> add("person:${it.id}:${event.startAt}") }
            }
            if (agentItems) {
                repo.agentQueue().filter { it.status.name == "OPEN" && (it.dueAt ?: 0L) <= nowMs }
                    .forEach { add("queue:${it.id}:${it.dueAt}") }
                repo.lastUserMessage()?.let { add("msg:${it.id}") }
            }
        }
    }

    private data class Week(
        val internal: List<CalendarEvent>,
        val system: List<CalendarEvent>,
        val google: List<CalendarEvent>,
        val googleLive: Boolean,
    ) {
        val all: List<CalendarEvent> get() = internal + system + google
    }

    /** Today and the next 7 days from every source. Mirroring keeps the live sources' rows fresh in Room. */
    private suspend fun week(now: ZonedDateTime, zone: ZoneId): Week {
        val from = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val to = now.toLocalDate().plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val googleLive = googleCalendar.available()
        val system = systemCalendar.mirror(from, to, excludeGoogleAccounts = googleLive)
        val google = googleCalendar.mirror(from, to)
        val internal = repo.calendarInRange(from, to).filter { it.source == CalendarSource.INTERNAL }
        return Week(internal, system, google, googleLive)
    }

    private suspend fun personName(reminder: Reminder): String =
        reminder.personId?.let { repo.getPerson(it)?.name } ?: "someone"

    private fun meeting(name: String, nextDay: List<CalendarEvent>): CalendarEvent? {
        val first = name.substringBefore(' ')
        return nextDay.firstOrNull {
            it.title.contains(name, ignoreCase = true) || (first.length >= 3 && it.title.contains(first, ignoreCase = true))
        }
    }

    /** A PERSON reminder names a calendar event in the next day with that person, so a pass can bring it up in time. */
    private suspend fun reminderLine(reminder: Reminder, nextDay: List<CalendarEvent>, zone: ZoneId): String {
        val body = when (reminder.trigger) {
            ReminderTrigger.TIME -> buildString {
                if (reminder.alarm) append("alarm, ")
                append(reminder.fireAt?.let { stamp(it, zone) } ?: "?")
                if (reminder.repeat != ReminderRepeat.NONE) append(", repeats ${reminder.repeat.name.lowercase()}")
                append(": \"${reminder.message}\"")
            }
            ReminderTrigger.PERSON -> {
                val name = personName(reminder)
                val meeting = meeting(name, nextDay)
                "next time with $name: \"${reminder.message}\"" +
                    (meeting?.let { " — $name is on the calendar: ${it.title} ${stamp(it.startAt, zone)}" } ?: "")
            }
            ReminderTrigger.PLACE -> "on arriving at ${reminder.place ?: "?"}: \"${reminder.message}\""
        }
        val condition = when (reminder.onlyIfEntityType) {
            "tasks" -> reminder.onlyIfEntityId?.let { repo.getTask(it) }?.let { " (only if task \"${it.title}\" is still open)" }
            "commitments" -> reminder.onlyIfEntityId?.let { repo.getCommitment(it) }?.let { " (only if \"${it.title}\" is still open)" }
            else -> null
        }.orEmpty()
        return "${reminder.id} | $body$condition"
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
