package com.her.reminders

import com.her.domain.Reminder

/** Records which reminders are armed, keyed by id, with the time each would ring. */
class FakeReminderAlarms : ReminderAlarms {
    val armed = linkedMapOf<String, Long>()

    override fun arm(reminder: Reminder) {
        reminder.fireAt?.let { armed[reminder.id] = it }
    }

    override fun disarm(id: String) {
        armed.remove(id)
    }
}
