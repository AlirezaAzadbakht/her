package com.her.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.her.core.newId
import com.her.core.nowMillis
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource

open class CalendarDataSource(private val context: Context) {
    open fun hasPermission(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    open fun eventsBetween(from: Long, to: Long): Result<List<CalendarEvent>> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Calendar permission is not granted"))
        }
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.CALENDAR_ID,
        )
        val items = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} != 1",
            arrayOf(from.toString(), to.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
            while (cursor.moveToNext()) {
                val now = nowMillis()
                items += CalendarEvent(
                    id = newId(),
                    title = cursor.getString(titleIdx) ?: "(untitled)",
                    startAt = cursor.getLong(startIdx),
                    endAt = cursor.getLong(endIdx).takeIf { it > 0 },
                    location = cursor.getString(locIdx),
                    notes = cursor.getString(descIdx),
                    externalId = cursor.getLong(idIdx).toString(),
                    source = CalendarSource.SYSTEM,
                    calendarId = cursor.getString(calIdx),
                    createdAt = now,
                    updatedAt = now,
                    deviceId = "system",
                    version = 1,
                    deletedAt = null,
                )
            }
        }
        return Result.success(items)
    }

    open fun createEvent(title: String, startAt: Long, endAt: Long?, notes: String?): Result<String> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Calendar permission is not granted"))
        }
        val calendarId = defaultCalendarId() ?: return Result.failure(IllegalStateException("No writable calendar"))
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startAt)
            put(CalendarContract.Events.DTEND, endAt ?: startAt + 60 * 60 * 1000)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.DESCRIPTION, notes)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return Result.failure(IllegalStateException("Could not create calendar event"))
        return Result.success(ContentUris.parseId(uri).toString())
    }

    open fun updateEvent(
        externalId: String,
        title: String?,
        startAt: Long?,
        endAt: Long?,
        notes: String?,
    ): Result<Unit> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Calendar permission is not granted"))
        }
        val id = externalId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("Invalid event id"))
        val values = ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it) }
            startAt?.let { put(CalendarContract.Events.DTSTART, it) }
            endAt?.let { put(CalendarContract.Events.DTEND, it) }
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        if (values.size() == 0) return Result.success(Unit)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val updated = context.contentResolver.update(uri, values, null, null)
        return if (updated > 0) Result.success(Unit) else Result.failure(IllegalStateException("Event was not updated"))
    }

    open fun deleteEvent(externalId: String): Result<Unit> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Calendar permission is not granted"))
        }
        val id = externalId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("Invalid event id"))
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val deleted = context.contentResolver.delete(uri, null, null)
        return if (deleted > 0) Result.success(Unit) else Result.failure(IllegalStateException("Event was not deleted"))
    }

    private fun defaultCalendarId(): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }
}
