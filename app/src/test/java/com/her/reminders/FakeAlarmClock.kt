package com.her.reminders

/** Stands in for the phone's clock app and keeps every alarm it was asked to set. */
class FakeAlarmClock : AlarmClockPort {
    data class Alarm(val hour: Int, val minute: Int, val label: String?, val days: List<Int>)

    val alarms = mutableListOf<Alarm>()

    override fun set(hour: Int, minute: Int, label: String?, days: List<Int>): Result<Unit> {
        alarms += Alarm(hour, minute, label, days)
        return Result.success(Unit)
    }

    fun rows(): List<Map<String, Any?>> = alarms.map { alarm ->
        mapOf(
            "hour" to alarm.hour,
            "minute" to alarm.minute,
            "label" to alarm.label,
            "days" to alarm.days.joinToString(","),
        )
    }
}
