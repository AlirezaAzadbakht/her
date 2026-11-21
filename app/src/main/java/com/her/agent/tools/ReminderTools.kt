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
import com.her.reminders.rearmAll
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.registerReminderTools() {
    register(
        "set_reminder",
        "Set a reminder they asked for, with exactly one of when, personName, or place.",
        objSchema(
            "message" to str("What to say when it fires, in their language"),
            "when" to str("Date and time of day: $WHEN_HINT"),
            "personName" to str("Fires the next time they are with this person"),
            "place" to str("Fires when they arrive at this place"),
            "onlyIfOpen" to str("Task or commitment id or title; skipped if that is already done"),
            "repeat" to enumField<ReminderRepeat>("Only for clock-time reminders"),
            "sourceMessageId" to str(),
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
        val message = args.requiredString("message")
        // Asked twice, or a retry after a timeout: hand back the reminder that already exists.
        if (personName == null) repo.reminders().firstOrNull {
            it.status == ReminderStatus.SCHEDULED && it.message == message && it.repeat == repeat && it.fireAt == fireAt && it.place == place
        }?.let { return@register reminderJson(it).put("ok", true).put("alreadySet", true) }
        val person = personName?.let { personFor(it) }
        val condition = args.optStringOrNull("onlyIfOpen")?.let { openRecordFor(it) }
        val now = nowMillis()
        val reminder = Reminder(
            id = newId(),
            message = message,
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
        val json = reminderJson(reminder).put("ok", true)
        if (place != null) {
            val saved = repo.placeNamed(place)
            val watched = saved != null && reminderAlarms.placesWatched()
            if (watched) reminderAlarms.armPlace(reminder, saved!!)
            json.put("firesOnArrival", watched)
            if (saved == null) json.put("note", "No saved place called '$place'. Ask them to tell you when they are there, then call save_place.")
            else if (!watched) json.put("note", placeHelp(location.problem() ?: "background"))
        }
        json
    }

    register(
        "get_weather",
        "Today's and tomorrow's forecast where they are. Use it when the weather changes what they should do: what to wear, an umbrella, plans outside. Say only what it returns, in plain words, never the numbers as a list.",
        objSchema(),
    ) { _ ->
        location.weatherProblem()?.let { return@register jsonObjectOf("ok" to false, "reason" to it, "error" to weatherHelp(it)) }
        val (lat, lng) = location.weatherPoint(fresh = runType == com.her.domain.AgentRunType.CHAT)
            ?: return@register jsonObjectOf("ok" to false, "reason" to "no_fix", "error" to "Could not get a location for the forecast. Ask them to try again in a moment.")
        val forecast = weather.forecast(lat, lng)
            ?: return@register jsonObjectOf("ok" to false, "reason" to "offline", "error" to "The forecast service did not answer. Say you couldn't check the weather just now.")
        forecast.put("ok", true).put("source", "Open-Meteo")
    }

    register(
        "save_place",
        "Save where they are right now under a name, when they say they are at a place (\"this is home\", \"I'm at the gym\"). Place reminders for that name then fire when they arrive. Only say it is saved when this returns ok.",
        objSchema("name" to str("Short name for the place, in their words, e.g. home, work, the gym"), required = listOf("name")),
    ) { args ->
        val name = args.requiredString("name").trim()
        location.problem()?.let { return@register jsonObjectOf("ok" to false, "reason" to it, "error" to placeHelp(it)) }
        val (lat, lng) = location.fix()
            ?: return@register jsonObjectOf("ok" to false, "reason" to "no_fix", "error" to "Could not get a location fix. Ask them to try again in a moment, ideally near a window.")
        val now = nowMillis()
        val existing = repo.placeNamed(name)
        repo.savePlace(com.her.data.db.PlaceEntity(existing?.id ?: newId(), existing?.name ?: name, lat, lng, PLACE_RADIUS_M,
            existing?.createdAt ?: now, now, repo.deviceId, (existing?.version ?: 0) + 1, null))
        reminderAlarms.rearmAll(repo)
        val waiting = repo.reminders().count { it.trigger == ReminderTrigger.PLACE && it.status == ReminderStatus.SCHEDULED && it.place.equals(name, ignoreCase = true) }
        jsonObjectOf("ok" to true, "name" to (existing?.name ?: name), "updated" to (existing != null), "remindersWaiting" to waiting)
            .also { if (!location.backgroundAllowed()) it.put("note", placeHelp("background")) }
    }

    register(
        "get_reminders",
        "List reminders.",
        objSchema("status" to enumField<ReminderStatus>()),
    ) { args ->
        val status = parseEnum<ReminderStatus>(args.optStringOrNull("status"))
        val items = repo.reminders().filter { status == null || it.status == status }
        jsonObjectOf("ok" to true, "reminders" to JSONArray(items.map { reminderJson(it) }))
    }

    register(
        "update_reminder",
        "Change a scheduled reminder instead of cancelling it and setting a new one.",
        objSchema(
            "id" to str(),
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
        if ((args.has("when") || args.has("repeat")) && com.her.companion.scheduleForReminder(repo, existing) != null) throw ToolValidationException("Change the linked responsibility schedule or its occurrence instead")
        val newMessage = args.optStringOrNull("message")?.takeIf { it.trim() != existing.message.trim() }
        if (newMessage != null && existing.repeat != ReminderRepeat.NONE) {
            throw ToolValidationException(
                "That is a repeating reminder; it keeps its words. For something new, call set_reminder and leave this one as it is. " +
                    "If they asked to reword this one, cancel_reminder it and set_reminder the new words with the same repeat.",
            )
        }
        val fireAt = args.optStringOrNull("when")?.let { reminderTime(it) }
        val trigger = if (fireAt != null) ReminderTrigger.TIME else existing.trigger
        val repeat = parseEnum<ReminderRepeat>(args.optStringOrNull("repeat")) ?: existing.repeat
        if (repeat != ReminderRepeat.NONE && trigger != ReminderTrigger.TIME) {
            throw ToolValidationException("Only a clock-time reminder can repeat")
        }
        val updated = existing.copy(
            message = newMessage ?: existing.message,
            trigger = trigger,
            fireAt = fireAt ?: existing.fireAt,
            personId = if (fireAt != null) null else existing.personId,
            place = if (fireAt != null) null else existing.place,
            repeat = repeat,
            updatedAt = nowMillis(),
            version = existing.version + 1,
        )
        repo.saveReminder(updated)
        if (existing.trigger == ReminderTrigger.PLACE && updated.trigger == ReminderTrigger.TIME) reminderAlarms.disarm(existing.id)
        if (updated.trigger == ReminderTrigger.TIME && updated.deviceId == repo.deviceId) reminderAlarms.arm(updated)
        reminderJson(updated).put("ok", true)
    }

    register(
        "cancel_reminder",
        "Cancel a reminder they no longer want.",
        objSchema("id" to str(), required = listOf("id")),
    ) { args ->
        val existing = requireReminder(args.requiredString("id"))
        repo.saveReminder(existing.copy(status = ReminderStatus.CANCELLED, updatedAt = nowMillis(), version = existing.version + 1))
        reminderAlarms.disarm(existing.id)
        jsonObjectOf("ok" to true, "id" to existing.id, "message" to existing.message)
    }

    register(
        "complete_reminder",
        "Mark a person or place reminder done once you have brought it up in the conversation.",
        objSchema("id" to str(), required = listOf("id")),
    ) { args ->
        val existing = requireReminder(args.requiredString("id"))
        if (existing.trigger == ReminderTrigger.TIME) throw ToolValidationException("Use manage_occurrence for repeating responsibilities; cancel_reminder for an unwanted clock reminder")
        val now = nowMillis()
        repo.saveReminder(existing.copy(status = ReminderStatus.FIRED, firedAt = now, updatedAt = now, version = existing.version + 1))
        reminderAlarms.disarm(existing.id)
        jsonObjectOf("ok" to true, "id" to existing.id)
    }

    register(
        "set_alarm",
        "Set a real alarm for \"wake me up at…\" or \"set an alarm\". She rings it herself, loudly, until they stop or snooze it, on any day ahead; days makes it repeat every week. If exact alarms are not allowed on this phone it goes to the clock app instead, which only takes a one-off alarm within the next 24 hours. Only say the alarm is set when this returns ok.",
        objSchema(
            "time" to str("When it should ring: $WHEN_HINT"),
            "label" to str("Short label shown when it rings, in their language"),
            "days" to arr("Weekdays to repeat on, in English (monday…sunday). Omit for a one-off alarm."),
            required = listOf("time"),
        ),
    ) { args ->
        val raw = args.requiredString("time")
        val dayNames = args.optStringList("days")
        val days = dayNames.map { day -> calendarWeekday(day) ?: throw ToolValidationException("Unknown weekday: $day") }
        val now = nowMillis()
        val parsed = parseWhen(raw) ?: throw ToolValidationException("Could not understand the time: $raw")
        if (parsed < now - 24 * HOUR_MS) throw ToolValidationException("That time has already passed: $raw")
        // A bare time means its next occurrence.
        var at = Instant.ofEpochMilli(parsed).atZone(nowZoned().zone)
        while (at.toInstant().toEpochMilli() <= now) at = at.plusDays(1)
        val clock = "%d:%02d".format(Locale.US, at.hour, at.minute)
        if (reminderAlarms.exactAllowed()) {
            val first = at
            val times = if (days.isEmpty()) listOf(first) else days.map { first.with(TemporalAdjusters.nextOrSame(dayOfWeek(it))) }
            val alarms = times.sortedBy { it.toInstant() }.map { time ->
                Reminder(
                    id = newId(),
                    message = args.optStringOrNull("label") ?: "⏰ $clock",
                    trigger = ReminderTrigger.TIME,
                    fireAt = time.toInstant().toEpochMilli(),
                    repeat = if (days.isEmpty()) ReminderRepeat.NONE else ReminderRepeat.WEEKLY,
                    personId = null,
                    place = null,
                    onlyIfEntityType = null,
                    onlyIfEntityId = null,
                    status = ReminderStatus.SCHEDULED,
                    firedAt = null,
                    sourceMessageId = null,
                    createdAt = now,
                    updatedAt = now,
                    deviceId = repo.deviceId,
                    version = 1,
                    deletedAt = null,
                    alarm = true,
                )
            }
            alarms.forEach {
                repo.saveReminder(it)
                reminderAlarms.arm(it)
            }
            return@register jsonObjectOf(
                "ok" to true,
                "id" to alarms.first().id,
                "ringsIn" to "Her",
                "clock" to clock,
                "when" to formatNaturalDate(Instant.ofEpochMilli(alarms.first().fireAt!!).atZone(nowZoned().zone)),
                "days" to JSONArray(dayNames),
            )
        }
        if (days.isEmpty() && at.toInstant().toEpochMilli() - now > 24 * HOUR_MS) {
            throw ToolValidationException(
                "Exact alarms are not allowed on this phone, so this goes to the clock app, which only takes a one-off alarm within the next 24 hours. " +
                    "Use set_reminder or pass days for a repeating alarm, and tell them to allow exact reminders in Settings so Her can ring alarms herself.",
            )
        }
        alarmClock.set(at.hour, at.minute, args.optStringOrNull("label"), days)
            .getOrElse { throw ToolValidationException(it.message ?: "Could not set the alarm") }
        jsonObjectOf(
            "ok" to true,
            "ringsIn" to "clock app",
            "clock" to clock,
            "when" to formatNaturalDate(at),
            "days" to JSONArray(dayNames),
        )
    }
}

private const val PLACE_RADIUS_M = 150f

/** What to tell them when a place reminder can't fire on arrival yet. */
private fun placeHelp(reason: String): String = when (reason) {
    "disabled" -> "Place reminders are turned off. They can turn them on in Settings."
    "location_permission" -> "Her isn't allowed to use location. They can allow it under Place reminders in Settings."
    "location_off" -> "Location is off on the phone. Place reminders work once they turn it on."
    else -> "To fire with Her closed, location must be set to \"Allow all the time\" (Settings, Place reminders)."
}

/** java.util.Calendar weekday (Sunday = 1) as a java.time DayOfWeek (Monday = 1). */
private fun dayOfWeek(calendarDay: Int): DayOfWeek = DayOfWeek.of((calendarDay + 5) % 7 + 1)

/** A reminder time must be in the future and name a time of day, so "Thursday" does not ring at midnight. */
private suspend fun ToolRegistry.reminderTime(raw: String): Long {
    val at = parseWhen(raw) ?: throw ToolValidationException("Could not understand the time: $raw")
    val local = Instant.ofEpochMilli(at).atZone(nowZoned().zone)
    val text = raw.lowercase(Locale.US)
    val saysMidnight = raw.trim().toLongOrNull() != null || 'T' in raw ||
        listOf("0:00", "00:", "12am", "12 am", "midnight").any { it in text }
    if (local.hour == 0 && local.minute == 0 && !saysMidnight) {
        throw ToolValidationException("Give a time of day too, for example 'Thursday at 9am'. To bring it up in your morning note that day instead, use add_agent_queue_item.")
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

private fun weatherHelp(reason: String): String = when (reason) {
    "disabled" -> "Weather is off. They can turn it on in Settings, under What she can use."
    else -> "Weather needs location access. They can allow it in Settings, under What she can use."
}
