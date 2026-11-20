package com.her.reminders

import com.her.data.db.PlaceEntity
import com.her.domain.Reminder

/** Records which reminders are armed, keyed by id, with the time each would ring or the place it waits for. */
class FakeReminderAlarms(var exact: Boolean = true, var watched: Boolean = true) : ReminderAlarms {
    val armed = linkedMapOf<String, Long>()
    val places = linkedMapOf<String, String>()

    override fun arm(reminder: Reminder) {
        reminder.fireAt?.let { armed[reminder.id] = it }
    }

    override fun armPlace(reminder: Reminder, place: PlaceEntity) {
        places[reminder.id] = place.name
    }

    override fun disarm(id: String) {
        armed.remove(id)
        places.remove(id)
    }

    override fun exactAllowed(): Boolean = exact

    override fun placesWatched(): Boolean = watched
}
