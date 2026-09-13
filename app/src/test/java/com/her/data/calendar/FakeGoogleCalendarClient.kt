package com.her.data.calendar

import com.her.core.nowMillis
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource

class FakeGoogleCalendarClient : GoogleCalendarClient() {
    data class Stored(
        val id: String,
        val title: String,
        val startAt: Long,
        val endAt: Long?,
        val notes: String?,
        val calendarId: String = "primary",
    )

    var granted: Boolean = false
    private val events = linkedMapOf<String, Stored>()
    private var nextId = 1L

    fun seed(
        title: String,
        startAt: Long,
        endAt: Long? = startAt + 60 * 60 * 1000,
        notes: String? = null,
    ): String {
        val id = "g${nextId++}"
        events[id] = Stored(id, title, startAt, endAt, notes)
        return id
    }

    fun rows(): List<Map<String, Any?>> = events.values.map { event ->
        mapOf(
            "id" to event.id,
            "title" to event.title,
            "startAt" to event.startAt,
            "endAt" to event.endAt,
            "notes" to event.notes,
            "externalId" to "google:${event.calendarId}:${event.id}",
            "source" to CalendarSource.GOOGLE.name,
        )
    }

    override suspend fun available(): Boolean = granted

    override suspend fun eventsBetween(from: Long, to: Long): Result<List<CalendarEvent>> {
        if (!granted) return Result.success(emptyList())
        val now = nowMillis()
        return Result.success(
            events.values
                .filter { CalendarWindows.overlaps(it.startAt, it.endAt, from, to) }
                .map { it.toDomain(now) },
        )
    }

    private fun Stored.toDomain(now: Long) = CalendarEvent(
        id = id,
        title = title,
        startAt = startAt,
        endAt = endAt,
        location = null,
        notes = notes,
        externalId = "google:$calendarId:$id",
        source = CalendarSource.GOOGLE,
        calendarId = calendarId,
        createdAt = now,
        updatedAt = now,
        deviceId = "google",
        version = 1,
        deletedAt = null,
    )
}
