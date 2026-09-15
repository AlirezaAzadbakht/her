package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.formatNaturalDate
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.optStringList
import com.her.core.optStringOrNull
import com.her.core.parseEnum
import com.her.core.requiredString
import com.her.data.repository.toJson
import com.her.domain.Person
import com.her.domain.Reminder
import com.her.domain.ReminderRepeat
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import java.time.Instant
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.registerReminderTools() {
    register(
        "set_reminder",
        "Set a reminder they asked for (\"remind me…\"). It notifies them on its own, even offline, so do not also create a task for it. Give exactly one trigger: when for a clock time, personName for \"next time I see X\", or place for \"when I get to X\". Use onlyIfOpen when it should fire only if a task or commitment is still not done.",
        objSchema(
            "message" to str("What to say when it fires, in their language, as you would say it at that moment"),
            "when" to str("Date and time of day: $WHEN_HINT"),
            "personName" to str("Fires the next time they are with this person"),
            "place" to str("Fires when they arrive at this place"),
            "onlyIfOpen" to str("Task or commitment id or title; the reminder is skipped if that is already done"),
            "repeat" to enumField<ReminderRepeat>("Only for clock-time reminders"),
            "sourceMessageId" to str("Origin message id"),
            required = listOf("message"),
        ),
    ) { args ->
        val whenRaw = args.optStringOrNull("when")
        val personName = args.optStringOrNull("personName")
        val place = args.optStringOrNull("place")
        if (listOfNotNull(whenRaw, personName, place).size != 1) {
            throw ToolValidationException("Give exactly one trigger: when, personName, or place")
        }
        val repeat = parseEnum<ReminderRepeat>(args.optStringOrNull("repeat")) ?: ReminderRepeat.NONE
        if (repeat != ReminderRepeat.NONE && whenRaw == null) {
            throw ToolValidationException("Only a clock-time reminder can repeat")
        }
        val fireAt = whenRaw?.let { reminderTime(it) }
        val person = personName?.let { personFor(it) }
        val condition = args.optStringOrNull("onlyIfOpen")?.let { openRecordFor(it) }
        val now = nowMillis()
        val reminder = Reminder(
            id = newId(),
            message = args.requiredString("message"),
            trigger = when {
                fireAt != null -> ReminderTrigger.TIME
                person != null -> ReminderTrigger.PERSON
                else -> ReminderTrigger.PLACE
            },
            fireAt = fireAt,
            repeat = repeat,
            personId = person?.id,
            place = place,
            onlyIfEntityType = condition?.first,
            onlyIfEntityId = condition?.second,
            status = ReminderStatus.SCHEDULED,
            firedAt = null,
            sourceMessageId = args.optStringOrNull("sourceMessageId"),
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
        )
        repo.saveReminder(reminder)
        if (reminder.trigger == ReminderTrigger.TIME) reminderAlarms.arm(reminder)
        reminderJson(reminder).put("ok", true)
    }

    register(
        "get_reminders",
        "List reminders. Scheduled ones are already in context under Reminders.",
        objSchema("status" to enumField<ReminderStatus>()),
    ) { args ->
        val status = parseEnum<ReminderStatus>(args.optStringOrNull("status"))
        val items = repo.reminders().filter { status == null || it.status == status }
        jsonObjectOf("ok" to true, "reminders" to JSONArray(items.map { reminderJson(it) }))
    }

    register(
        "update_reminder",
        "Change a scheduled reminder: its words, its time, or how it repeats. Use this to move a reminder instead of cancelling and setting a new one. Passing when turns a person or place reminder into a clock one.",
        objSchema(
            "id" to str("Reminder id from context"),
            "message" to str(),
            "when" to str("Date and time of day: $WHEN_HINT"),
            "repeat" to enumField<ReminderRepeat>(),
            required = listOf("id"),
        ),
    ) { args ->
        val existing = requireReminder(args.requiredString("id"))
        if (existing.status != ReminderStatus.SCHEDULED) {
            throw ToolValidationException("That reminder is already ${existing.status.name.lowercase()}; set a new one instead")
        }
        val fireAt = args.optStringOrNull("when")?.let { reminderTime(it) }
        val trigger = if (fireAt != null) ReminderTrigger.TIME else existing.trigger
        val repeat = parseEnum<ReminderRepeat>(args.optStringOrNull("repeat")) ?: existing.repeat
        if (repeat != ReminderRepeat.NONE && trigger != ReminderTrigger.TIME) {
            throw ToolValidationException("Only a clock-time reminder can repeat")
        }
        val updated = existing.copy(
            message = args.optStringOrNull("message") ?: existing.message,
            trigger = trigger,
            fireAt = fireAt ?: existing.fireAt,
            personId = if (fireAt != null) null else existing.personId,
            place = if (fireAt != null) null else existing.place,
            repeat = repeat,
            updatedAt = nowMillis(),
            version = existing.version + 1,
        )
        repo.saveReminder(updated)
        if (updated.trigger == ReminderTrigger.TIME && updated.deviceId == repo.deviceId) reminderAlarms.arm(updated)
        reminderJson(updated).put("ok", true)
    }

    register(
        "cancel_reminder",
        "Cancel a reminder they no longer want.",
        objSchema("id" to str("Reminder id from context"), required = listOf("id")),
    ) { args ->
        val existing = requireReminder(args.requiredString("id"))
        repo.saveReminder(existing.copy(status = ReminderStatus.CANCELLED, updatedAt = nowMillis(), version = existing.version + 1))
        reminderAlarms.disarm(existing.id)
        jsonObjectOf("ok" to true, "id" to existing.id)
    }

    register(
        "complete_reminder",
        "Mark a person or place reminder done once you have brought it up in the conversation.",
        objSchema("id" to str("Reminder id from context"), required = listOf("id")),
    ) { args ->
        val existing = requireReminder(args.requiredString("id"))
        val now = nowMillis()
        repo.saveReminder(existing.copy(status = ReminderStatus.FIRED, firedAt = now, updatedAt = now, version = existing.version + 1))
        reminderAlarms.disarm(existing.id)
        jsonObjectOf("ok" to true, "id" to existing.id)
    }

    register(
        "set_alarm",
        "Set a real alarm in the phone's clock app, for \"wake me up at…\" or \"set an alarm\". A one-off alarm must ring within the next 24 hours; days makes it repeat every week. Only say the alarm is set when this returns ok.",
        objSchema(
            "time" to str("When it should ring: $WHEN_HINT"),
            "label" to str("Short label shown on the alarm"),
            "days" to arr("Weekdays to repeat on, in English (monday…sunday). Omit for a one-off alarm."),
            required = listOf("time"),
        ),
    ) { args ->
        val raw = args.requiredString("time")
        val dayNames = args.optStringList("days")
        val days = dayNames.map { day -> calendarWeekday(day) ?: throw ToolValidationException("Unknown weekday: $day") }
        val now = nowMillis()
        val parsed = requiredWhen(raw) ?: throw ToolValidationException("Could not understand the time: $raw")
        if (parsed < now - 24 * HOUR_MS) throw ToolValidationException("That time has already passed: $raw")
        // The clock app rings at the next occurrence of that hour and minute.
        var at = Instant.ofEpochMilli(parsed).atZone(nowZoned().zone)
        while (at.toInstant().toEpochMilli() <= now) at = at.plusDays(1)
        if (days.isEmpty() && at.toInstant().toEpochMilli() - now > 24 * HOUR_MS) {
            throw ToolValidationException(
                "A one-off alarm can only ring within the next 24 hours. For a later day, use set_reminder, or pass days for a repeating alarm.",
            )
        }
        alarmClock.set(at.hour, at.minute, args.optStringOrNull("label"), days)
            .getOrElse { throw ToolValidationException(it.message ?: "Could not set the alarm") }
        jsonObjectOf(
            "ok" to true,
            "clock" to "%d:%02d".format(Locale.US, at.hour, at.minute),
            "when" to formatNaturalDate(at),
            "days" to JSONArray(dayNames),
        )
    }
}

/** A reminder time must be in the future and name a time of day, so "Thursday" does not ring at midnight. */
private suspend fun ToolRegistry.reminderTime(raw: String): Long {
    val at = requiredWhen(raw) ?: throw ToolValidationException("Could not understand the time: $raw")
    val local = Instant.ofEpochMilli(at).atZone(nowZoned().zone)
    val text = raw.lowercase(Locale.US)
    val saysMidnight = raw.trim().toLongOrNull() != null || 'T' in raw ||
        listOf("0:00", "00:", "12am", "12 am", "midnight").any { it in text }
    if (local.hour == 0 && local.minute == 0 && !saysMidnight) {
        throw ToolValidationException("Give a time of day too, for example 'Thursday at 9am'")
    }
    if (at <= nowMillis()) throw ToolValidationException("That time has already passed: $raw")
    return at
}

private suspend fun ToolRegistry.personFor(name: String): Person {
    repo.getPerson(name)?.let { return it }
    val people = repo.people()
    people.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
    val first = name.substringBefore(' ')
    people.filter { it.name.substringBefore(' ').equals(first, ignoreCase = true) }.singleOrNull()?.let { return it }
    val now = nowMillis()
    return Person(newId(), name, null, null, null, null, now, 0.7, now, now, repo.deviceId, 1, null)
        .also { repo.savePerson(it) }
}

private suspend fun ToolRegistry.openRecordFor(idOrTitle: String): Pair<String, String> {
    runCatching { requireTask(idOrTitle) }.getOrNull()?.let { return "tasks" to it.id }
    runCatching { requireCommitment(idOrTitle) }.getOrNull()?.let { return "commitments" to it.id }
    throw ToolValidationException("No single task or commitment matches '$idOrTitle'. Create it first, then pass its id.")
}

private suspend fun ToolRegistry.requireReminder(idOrText: String): Reminder {
    repo.getReminder(idOrText)?.takeIf { it.deletedAt == null }?.let { return it }
    val matches = repo.reminders()
        .filter { it.status == ReminderStatus.SCHEDULED && it.message.contains(idOrText, ignoreCase = true) }
    return matches.singleOrNull()
        ?: throw ToolValidationException(
            if (matches.isEmpty()) "Reminder not found: $idOrText" else "Several reminders match '$idOrText'; pass the id from context",
        )
}

private suspend fun ToolRegistry.reminderJson(reminder: Reminder): JSONObject {
    val json = JSONObject(reminder.toJson())
    reminder.fireAt?.let { json.put("when", formatNaturalDate(Instant.ofEpochMilli(it).atZone(nowZoned().zone))) }
    reminder.personId?.let { repo.getPerson(it) }?.let { json.put("person", it.name) }
    return json
}

private fun calendarWeekday(raw: String): Int? = when (raw.trim().lowercase(Locale.US).take(3)) {
    "sun" -> Calendar.SUNDAY
    "mon" -> Calendar.MONDAY
    "tue" -> Calendar.TUESDAY
    "wed" -> Calendar.WEDNESDAY
    "thu" -> Calendar.THURSDAY
    "fri" -> Calendar.FRIDAY
    "sat" -> Calendar.SATURDAY
    else -> null
}
