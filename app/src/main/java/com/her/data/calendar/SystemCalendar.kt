package com.her.data.calendar

import com.her.core.newId
import com.her.core.nowMillis
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource

class SystemCalendar(
    private val repo: HerRepository,
    private val device: CalendarDataSource,
    private val settings: AppSettingsStore,
) {
    fun live(): Boolean = settings.read().calendarEnabled && device.hasPermission()

    suspend fun mirror(from: Long, to: Long, excludeGoogleAccounts: Boolean = false): List<CalendarEvent> {
        if (!live()) return emptyList()
        val remote = device.eventsBetween(from, to, excludeGoogleAccounts).getOrDefault(emptyList())
        val now = nowMillis()
        val kept = remote.mapNotNull { incoming ->
            val externalId = incoming.externalId ?: return@mapNotNull null
            val existing = repo.getCalendarByExternalId(externalId)
            val row = (existing ?: CalendarEvent(
                id = newId(),
                title = incoming.title,
                startAt = incoming.startAt,
                endAt = incoming.endAt,
                location = incoming.location,
                notes = incoming.notes,
                externalId = externalId,
                source = CalendarSource.SYSTEM,
                calendarId = incoming.calendarId,
                createdAt = now,
                updatedAt = now,
                deviceId = repo.deviceId,
                version = 1,
                deletedAt = null,
            )).copy(
                title = incoming.title,
                startAt = incoming.startAt,
                endAt = incoming.endAt,
                location = incoming.location,
                notes = incoming.notes,
                externalId = externalId,
                source = CalendarSource.SYSTEM,
                calendarId = incoming.calendarId ?: existing?.calendarId,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
                deletedAt = null,
            )
            repo.saveCalendarEvent(row)
            row
        }
        val seen = kept.mapNotNull { it.externalId }.toSet()
        repo.calendarInRange(from, to)
            .filter { it.source == CalendarSource.SYSTEM && it.externalId != null && it.externalId !in seen }
            .forEach { repo.deleteCalendarEvent(it.id) }
        return kept
    }

    suspend fun create(title: String, startAt: Long, endAt: Long?, notes: String?): String? {
        if (!live()) return null
        return device.createEvent(title, startAt, endAt, notes).getOrNull()
    }

    suspend fun push(
        event: CalendarEvent,
        title: String,
        startAt: Long,
        endAt: Long?,
        notes: String?,
    ): CalendarEvent {
        if (!live()) {
            return event.copy(title = title, startAt = startAt, endAt = endAt, notes = notes)
        }
        val externalId = event.externalId
        if (externalId != null) {
            device.updateEvent(externalId, title, startAt, endAt, notes)
            return event.copy(
                title = title,
                startAt = startAt,
                endAt = endAt,
                notes = notes,
                externalId = externalId,
                source = CalendarSource.SYSTEM,
            )
        }
        val created = device.createEvent(title, startAt, endAt, notes).getOrNull()
        return event.copy(
            title = title,
            startAt = startAt,
            endAt = endAt,
            notes = notes,
            externalId = created ?: event.externalId,
            source = if (created != null) CalendarSource.SYSTEM else event.source,
        )
    }

    suspend fun delete(event: CalendarEvent) {
        val externalId = event.externalId
        if (live() && externalId != null) {
            device.deleteEvent(externalId)
        }
    }
}
