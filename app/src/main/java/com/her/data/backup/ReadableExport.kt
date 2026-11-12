package com.her.data.backup

import com.her.data.repository.HerRepository
import com.her.domain.ReminderStatus
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Everything she keeps about them as one Markdown file a person can read, unlike the backup zip. */
object ReadableExport {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    suspend fun markdown(repo: HerRepository): String {
        val zone = repo.profileZone()
        fun at(millis: Long?): String = millis?.let { " (${Instant.ofEpochMilli(it).atZone(zone).format(stamp)})" }.orEmpty()
        fun words(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" — ")
        val out = StringBuilder("# What Her knows\n\nExported ${repo.now().format(stamp)}\n")
        fun section(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            out.append("\n## ").append(title).append("\n\n")
            lines.forEach { out.append("- ").append(it.replace("\n", " ")).append('\n') }
        }
        section("About you", repo.activeUserUnderstandings().map { "${it.facet}: ${it.content}" })
        section("Memories", repo.activeLong().map { it.content })
        section("People", repo.people().map { words(it.name, it.relationship, it.birthday?.let { b -> "birthday $b" }, it.importantNotes, it.preferences) })
        section("Projects", repo.projects().map { words(it.name, it.summary ?: it.description) })
        section("Goals", repo.goals().map { "[${it.status}] " + words(it.title, it.progressSummary) + at(it.targetDate) })
        section("Tasks", repo.tasks().map { "[${it.status}] " + words(it.title, it.description) + at(it.dueAt) })
        section("Commitments", repo.commitments().map { "[${it.status}] " + words(it.title, it.promisedTo?.let { p -> "to $p" }) + at(it.dueAt) })
        section("Waiting on", repo.openLoops().map { "[${it.status}] ${it.description}" })
        section("Routines", repo.routines().map { words(it.title, it.schedule) })
        section("Important dates", repo.importantDates().map { words(it.dateIso, it.title, it.recurrence) })
        section(
            "Reminders",
            repo.reminders().filter { it.status == ReminderStatus.SCHEDULED }.map { it.message + at(it.fireAt) },
        )
        repo.groceries().groupBy { it.list }.forEach { (list, items) ->
            section("List: $list", items.map { "[${it.status}] " + words(it.name, it.quantity, it.notes) })
        }
        return out.toString()
    }
}
