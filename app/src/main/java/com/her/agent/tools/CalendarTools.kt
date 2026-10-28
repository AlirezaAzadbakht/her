package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.formatNaturalDate
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.optLongOrNull
import com.her.core.optStringOrNull
import com.her.core.requiredString
import com.her.data.calendar.CalendarWindows
import com.her.data.repository.toJson
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import com.her.domain.ConfirmationKind
import com.her.domain.PendingConfirmation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.registerCalendarTools() {
    register(
        "get_calendar_events",
        "Read internal, device, and Google events for any slice; context only has the next 7 days.",
        objSchema(
            "from" to str("Slice start: $WHEN_HINT"),
            "to" to str("Slice end: $WHEN_HINT. A date alone includes that whole day."),
            "days" to num("Days from the start when to is omitted. Default 7."),
        ),
    ) { args ->
        val slice = try {
            CalendarWindows.resolveSlice(
                now = nowZoned(),
                fromPhrase = args.optStringOrNull("from"),
                toPhrase = args.optStringOrNull("to"),
                days = args.optLongOrNull("days"),
            )
        } catch (e: IllegalArgumentException) {
            throw ToolValidationException(e.message ?: "Could not understand the time slice")
        }
        val zone = nowZoned().zone
        val googleConnected = googleCalendar.available()
        val system = systemCalendar.mirror(slice.from, slice.to, excludeGoogleAccounts = googleConnected)
        val google = googleCalendar.mirror(slice.from, slice.to)
        val internal = repo.calendarInRange(slice.from, slice.to).filter { it.source == CalendarSource.INTERNAL }
        jsonObjectOf(
            "ok" to true,
            "from" to slice.from,
            "to" to slice.to,
            "fromWhen" to formatNaturalDate(Instant.ofEpochMilli(slice.from).atZone(zone)),
            "toWhen" to formatNaturalDate(Instant.ofEpochMilli(slice.to).atZone(zone)),
            "calendarPermission" to calendar.hasPermission(),
            "calendarEnabled" to settings.read().calendarEnabled,
            "googleConnected" to googleConnected,
            "internal" to JSONArray(internal.map { calendarJson(it, zone) }),
            "system" to JSONArray(system.map { calendarJson(it, zone) }),
            "google" to JSONArray(google.map { calendarJson(it, zone) }),
        )
    }
    register(
        "create_calendar_event",
        "Create an event on the device calendar when access is on, and keep an internal copy.",
        objSchema(
            "title" to str(),
            "when" to str(WHEN_HINT),
            "notes" to str(),
            required = listOf("title", "when"),
        ),
    ) { args ->
        val start = parseWhen(args.requiredString("when")) ?: throw ToolValidationException("Could not understand the time")
        val end = start + HOUR_MS
        val now = nowMillis()
        val id = newId()
        val title = args.requiredString("title")
        val notes = args.optStringOrNull("notes")
        val externalId = systemCalendar.create(title, start, end, notes)
        repo.saveCalendarEvent(
            CalendarEvent(
                id, title, start, end, null, notes, externalId,
                if (externalId != null) CalendarSource.SYSTEM else CalendarSource.INTERNAL,
                null, now, now, repo.deviceId, 1, null,
            ),
        )
        jsonObjectOf("ok" to true, "id" to id, "externalId" to externalId, "systemWrite" to (externalId != null))
    }
    register(
        "update_calendar_event",
        "Move or rename one calendar event by id. If two events could match, ask which one and do not call this.",
        objSchema(
            "id" to str(),
            "title" to str(),
            "when" to str(WHEN_HINT),
            "notes" to str(),
            required = listOf("id"),
        ),
    ) { args ->
        val existing = requireCalendarEvent(args.requiredString("id"))
        refuseGoogleWrite(existing)
        val title = args.optStringOrNull("title") ?: existing.title
        val start = args.optStringOrNull("when")?.let {
            parseWhen(it) ?: throw ToolValidationException("Could not understand the time")
        } ?: existing.startAt
        val duration = (existing.endAt ?: (existing.startAt + HOUR_MS)) - existing.startAt
        val end = if (args.optStringOrNull("when") != null) start + duration else existing.endAt
        val notes = args.optStringOrNull("notes") ?: existing.notes
        val pushed = systemCalendar.push(existing, title, start, end, notes)
        repo.saveCalendarEvent(pushed.copy(updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true, "id" to existing.id, "systemWrite" to (pushed.externalId != null && systemCalendar.live()))
    }
    register(
        "delete_calendar_event",
        "Delete a calendar event. Call it before asking: a phone-calendar event returns needsConfirmation, and on their next message you pass the id from Pending confirmations as confirmId.",
        objSchema(
            "id" to str(),
            "confirmId" to str("From a previous needsConfirmation result. Do not reuse the event id."),
            "confirmed" to bool("Ignored"),
            required = listOf("id"),
        ),
    ) { args ->
        val existing = requireCalendarEvent(args.requiredString("id"))
        refuseGoogleWrite(existing)
        if (existing.source != CalendarSource.INTERNAL && existing.externalId != null) {
            val confirmId = args.optStringOrNull("confirmId")
            if (confirmId == null) {
                val pending = PendingConfirmation(
                    newId(),
                    ConfirmationKind.CALENDAR_DELETE,
                    "Delete external event: ${existing.title}",
                    JSONObject().put("id", existing.id).toString(),
                    nowMillis(),
                )
                repo.saveConfirmation(pending)
                return@register jsonObjectOf(
                    "ok" to false,
                    "needsConfirmation" to true,
                    "confirmId" to pending.id,
                    "summary" to pending.summary,
                )
            }
            consumeConfirmation(confirmId, ConfirmationKind.CALENDAR_DELETE)
            systemCalendar.delete(existing)
        }
        repo.deleteCalendarEvent(existing.id)
        jsonObjectOf("ok" to true)
    }
}

private suspend fun ToolRegistry.calendarWindow(days: Long): Pair<Long, Long> {
    val now = nowZoned()
    val from = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
    val to = now.toLocalDate().plusDays(days).atStartOfDay(now.zone).toInstant().toEpochMilli()
    return from to to
}

private suspend fun ToolRegistry.requireCalendarEvent(idOrTitle: String): CalendarEvent {
    repo.getCalendarEvent(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
    repo.getCalendarByExternalId(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
    val (from, to) = calendarWindow(400)
    val googleConnected = googleCalendar.available()
    systemCalendar.mirror(from, to, excludeGoogleAccounts = googleConnected)
    googleCalendar.mirror(from, to)
    repo.getCalendarEvent(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
    repo.getCalendarByExternalId(idOrTitle)?.takeIf { it.deletedAt == null }?.let { return it }
    val all = repo.calendarInRange(from, to)
    all.firstOrNull { it.id.equals(idOrTitle, ignoreCase = true) }?.let { return it }
    val exact = all.filter { it.title.equals(idOrTitle, ignoreCase = true) }
    if (exact.size == 1) return exact.first()
    val loose = all.filter { it.title.contains(idOrTitle, ignoreCase = true) }
    return loose.singleOrNull()
        ?: throw ToolValidationException(
            if (exact.isEmpty() && loose.isEmpty()) "Event not found: $idOrTitle"
            else "Multiple events match '$idOrTitle'; pass the id from context",
        )
}

private fun calendarJson(event: CalendarEvent, zone: ZoneId): JSONObject {
    val obj = JSONObject(event.toJson())
    obj.put("when", formatNaturalDate(Instant.ofEpochMilli(event.startAt).atZone(zone)))
    event.endAt?.let {
        obj.put(
            "until",
            Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
        )
    }
    return obj
}

private fun refuseGoogleWrite(event: CalendarEvent) {
    if (event.source == CalendarSource.GOOGLE) {
        throw ToolValidationException(
            "Google Calendar events are read-only. Change them in Google Calendar.",
        )
    }
}
