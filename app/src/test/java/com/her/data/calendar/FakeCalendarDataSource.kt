package com.her.data.calendar

import android.content.Context
import com.her.core.nowMillis
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource

class FakeCalendarDataSource(
    context: Context,
    var granted: Boolean = false,
) : CalendarDataSource(context) {
    data class Stored(
        val id: String,
        val title: String,
        val startAt: Long,
        val endAt: Long?,
        val notes: String?,
        val calendarId: String = "1",
    )

    private val events = linkedMapOf<String, Stored>()
    private var nextId = 1L

    fun all(): List<Stored> = events.values.toList()

    fun seed(title: String, startAt: Long, endAt: Long? = startAt + 60 * 60 * 1000, notes: String? = null): String {
        val id = (nextId++).toString()
        events[id] = Stored(id, title, startAt, endAt, notes)
        return id
    }

    fun rows(): List<Map<String, Any?>> = all().map { event ->
        mapOf(
            "id" to event.id,
            "title" to event.title,
            "startAt" to event.startAt,
            "endAt" to event.endAt,
            "notes" to event.notes,
            "externalId" to event.id,
            "source" to CalendarSource.SYSTEM.name,
        )
    }

    override fun hasPermission(): Boolean = granted

    override fun eventsBetween(from: Long, to: Long): Result<List<CalendarEvent>> {
        if (!granted) return Result.failure(IllegalStateException("Calendar permission is not granted"))
        val now = nowMillis()
        return Result.success(
            events.values.filter { it.startAt in from..to }.map { it.toDomain(now) },
        )
    }

    override fun createEvent(title: String, startAt: Long, endAt: Long?, notes: String?): Result<String> {
        if (!granted) return Result.failure(IllegalStateException("Calendar permission is not granted"))
        return Result.success(seed(title, startAt, endAt, notes))
    }

    override fun updateEvent(
        externalId: String,
        title: String?,
        startAt: Long?,
        endAt: Long?,
        notes: String?,
    ): Result<Unit> {
        if (!granted) return Result.failure(IllegalStateException("Calendar permission is not granted"))
        val existing = events[externalId]
            ?: return Result.failure(IllegalStateException("Event was not updated"))
        events[externalId] = existing.copy(
            title = title ?: existing.title,
            startAt = startAt ?: existing.startAt,
            endAt = endAt ?: existing.endAt,
            notes = notes ?: existing.notes,
        )
        return Result.success(Unit)
    }

    override fun deleteEvent(externalId: String): Result<Unit> {
        if (!granted) return Result.failure(IllegalStateException("Calendar permission is not granted"))
        return if (events.remove(externalId) != null) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Event was not deleted"))
        }
    }

    private fun Stored.toDomain(now: Long) = CalendarEvent(
        id = id,
        title = title,
        startAt = startAt,
        endAt = endAt,
        location = null,
        notes = notes,
        externalId = id,
        source = CalendarSource.SYSTEM,
        calendarId = calendarId,
        createdAt = now,
        updatedAt = now,
        deviceId = "system",
        version = 1,
        deletedAt = null,
    )
}
