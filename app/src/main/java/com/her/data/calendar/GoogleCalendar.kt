package com.her.data.calendar

import com.her.core.newId
import com.her.core.nowMillis
import com.her.data.repository.HerRepository
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource

class GoogleCalendar(
    private val repo: HerRepository,
    private val client: GoogleCalendarClient,
) {
    @Volatile
    private var lastFetchAt = 0L

    @Volatile
    private var lastFrom = 0L

    @Volatile
    private var lastTo = 0L

    @Volatile
    private var lastResult: List<CalendarEvent>? = null

    suspend fun available(): Boolean = client.available()

    suspend fun mirror(from: Long, to: Long): List<CalendarEvent> {
        val cachedRoom = suspend {
            repo.calendarInRange(from, to).filter { it.source == CalendarSource.GOOGLE }
        }
        if (!client.available()) return cachedRoom()
        val now = nowMillis()
        val cached = lastResult
        if (cached != null && lastFrom == from && lastTo == to && now - lastFetchAt < CalendarWindows.GOOGLE_TTL_MS) {
            return cached
        }
        val remote = client.eventsBetween(from, to)
        if (remote.isFailure) return cachedRoom()
        val kept = upsert(remote.getOrDefault(emptyList()), from, to, now)
        lastFetchAt = now
        lastFrom = from
        lastTo = to
        lastResult = kept
        return kept
    }

    private suspend fun upsert(
        remote: List<CalendarEvent>,
        from: Long,
        to: Long,
        now: Long,
    ): List<CalendarEvent> {
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
                source = CalendarSource.GOOGLE,
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
                source = CalendarSource.GOOGLE,
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
            .filter { it.source == CalendarSource.GOOGLE && it.externalId != null && it.externalId !in seen }
            .forEach { repo.deleteCalendarEvent(it.id) }
        return kept
    }
}
