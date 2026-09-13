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

    open fun eventsBetween(
        from: Long,
        to: Long,
        excludeGoogleAccounts: Boolean = false,
    ): Result<List<CalendarEvent>> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Calendar permission is not granted"))
        }
        val skipCalendars = if (excludeGoogleAccounts) googleCalendarIds() else emptySet()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, from)
            ContentUris.appendId(builder, to)
        }.build()
        val items = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
            val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val now = nowMillis()
            while (cursor.moveToNext()) {
                val calendarId = cursor.getString(calIdx)
                if (calendarId != null && calendarId in skipCalendars) continue
                val eventId = cursor.getLong(idIdx)
                val begin = cursor.getLong(startIdx)
                items += CalendarEvent(
                    id = newId(),
                    title = cursor.getString(titleIdx) ?: "(untitled)",
                    startAt = begin,
                    endAt = cursor.getLong(endIdx).takeIf { it > 0 },
                    location = cursor.getString(locIdx),
                    notes = cursor.getString(descIdx),
                    externalId = CalendarWindows.instanceExternalId(eventId.toString(), begin),
                    source = CalendarSource.SYSTEM,
                    calendarId = calendarId,
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
        val eventId = ContentUris.parseId(uri)
        return Result.success(CalendarWindows.instanceExternalId(eventId.toString(), startAt))
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
        val id = CalendarWindows.parseDeviceEventId(externalId)
            ?: return Result.failure(IllegalArgumentException("Invalid event id"))
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
        val id = CalendarWindows.parseDeviceEventId(externalId)
            ?: return Result.failure(IllegalArgumentException("Invalid event id"))
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

    private fun googleCalendarIds(): Set<String> {
        val ids = mutableSetOf<String>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CalendarWindows.GOOGLE_ACCOUNT_TYPE),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            while (cursor.moveToNext()) {
                ids += cursor.getLong(idIdx).toString()
            }
        }
        return ids
    }
}
