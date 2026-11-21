package com.her.data.device

import android.app.AlarmManager
import android.content.Context
import android.os.BatteryManager
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ALARM_HORIZON_MS = 24 * 60 * 60 * 1000L
private const val LOW_BATTERY = 30
private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** The next alarm and the battery, read from the phone. No permission needed. */
fun androidPhoneLines(context: Context): (ZonedDateTime) -> List<String> = { now ->
    val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
    val battery = context.getSystemService(BatteryManager::class.java)
    phoneLines(
        alarm,
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
        battery?.isCharging == true,
        now,
    )
}

/** Lines only when there's something to say, so the context stays the same most of the day. */
fun phoneLines(nextAlarmAt: Long?, batteryPct: Int?, charging: Boolean, now: ZonedDateTime): List<String> = buildList {
    val nowMs = now.toInstant().toEpochMilli()
    if (nextAlarmAt != null && nextAlarmAt > nowMs && nextAlarmAt - nowMs <= ALARM_HORIZON_MS) {
        val at = Instant.ofEpochMilli(nextAlarmAt).atZone(now.zone)
        val day = if (at.toLocalDate() == now.toLocalDate()) "today" else "tomorrow"
        add("Next alarm: ${clock.format(at)} $day")
    }
    if (batteryPct != null && batteryPct <= LOW_BATTERY && !charging) add("Battery $batteryPct%, not charging")
}
