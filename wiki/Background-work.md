# Background work

`app/src/main/java/com/her/work/Workers.kt`. `HerApplication` calls `scheduler.enqueueAll()` at process start. `BootReceiver` re-enqueues after `BOOT_COMPLETED`. Settings changes also call `enqueueAll()` so a quiet-hours edit can move the briefing.

`BackgroundRunPolicy` decides whether a wakeup should run, skip, or defer. Manual developer buttons (`runHourlyNow()`, `runNightlyNow()`) bypass quiet-hours and low-battery gates.

## Schedule

| Worker | Unique name | When | Constraints |
|--------|-------------|------|-------------|
| `HourlyWorker` | `her-hourly` | Periodic, every 1 hour | Network + battery not low |
| `NightlyWorker` | `her-nightly` | One-shot at **03:00** local; reschedules the next night when it finishes | Network |
| `BriefingWorker` | `her-briefing` | One-shot at **quiet-hours end** (default 08:00); reschedules the next morning | Network |
| `SyncWorker` | `her-sync` | Periodic, every 6 hours; no-ops if Drive is disabled or battery is low | Network + battery not low |
| `OutboxWorker` | `her-outbox` | One-shot when `PENDING` user rows exist | Network |

Nightly and briefing are **not** 24-hour periodic work. A one-shot delayed to the wall clock keeps the 50-call nightly pass at night. After success, skip, or LLM failure the worker appends the next occurrence. `enqueueAll()` / boot restore a missing next run without resetting an already-pending one.

A schedule fingerprint (`hourly:1h|nightly:03:00|briefing:{quietEnd}|sync:6h|v2`) is stored in app settings. Periodic work uses `UPDATE` only when that fingerprint changes; otherwise `KEEP`, so opening the app does not push the next hourly out by another hour.

## Policy

| Pass | Skip | Defer (`Result.retry`) | Run |
|------|------|------------------------|-----|
| Hourly | LLM not configured; last **success** &lt; 20 minutes; battery ≤15% and not charging; quiet hours (default) | — | Otherwise. Orchestrator uses CATCH_UP if the last success was &gt; 3 hours ago (including after a quiet night). |
| Nightly | Already completed today; LLM not configured | Battery ≤15% and not charging | Otherwise. Nightly does **not** require charging. |
| Briefing | Already generated today; LLM not configured; battery ≤15% and not charging | — | Otherwise |
| Sync | Drive off; battery ≤15% and not charging | — | Otherwise |

LLM failures return `Result.success()` and wait for the next scheduled slot. The only retry is nightly on critically low battery.

Hourly stamps `lastHourlyRunAt` only after a successful pass.

## Quiet hours

Default **23:30–08:00** (`QuietHours` in `Core.kt`). Overnight window: a time is “inside” if it is after start **or** before end.

Hourly LLM passes skip quiet hours unless Settings → **Hourly during quiet hours** is on. Nightly still consolidates at 03:00. The first daytime hourly (and the morning briefing) cover the overnight gap.

`NotificationPolicy`:

- Non-urgent notifications are suppressed during quiet hours
- Duplicate key cooldown: 45 minutes
- Gap between non-urgent notifications: 20 minutes

The morning briefing is scheduled at the **end** of quiet hours, not at a fixed 8:00 if the user changed the window.

## Foreground and battery exemption

Hourly, nightly, and briefing call `setForeground()` on a low-importance **Background work** channel so a long LLM pass is not killed at WorkManager’s ~10 minute limit. Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`.

Settings, under quiet hours: if the app is still battery-optimized, **Allow background work** opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. This is user-initiated so OEM savers do not silently defer hourly/nightly. There is no first-launch prompt.

## Budgets

See [Agent](Agent.md). Hourly ≤ 10 LLM calls, nightly ≤ 50, briefing ≤ 8. Chat uses the settings limit (default 12).

## Notifications

`app/src/main/java/com/her/notify/Notifications.kt`. Hourly’s default is silence. A user-facing message from an autonomous pass is the exception, not the loop.

Actions on a notification:

- **Reply**: inline text. The reply is enqueued as a user message and the outbox worker runs it as a normal chat turn.
- **Later**: queues an agent follow-up for tomorrow that quotes the notification text.
- **Open**: opens the app.

`NotificationActions.decide` holds that logic so it is unit-tested without Android. The hourly quiet-hours check uses the profile timezone, like the rest of the agent.

Any autonomous text that reaches the phone is also saved as her latest assistant message, so the screen shows what the notification said.

## Reminders

Clock reminders do not wait for WorkManager. `app/src/main/java/com/her/reminders/Reminders.kt`:

- `AndroidReminderAlarms` arms one `AlarmManager` alarm per reminder: `setExactAndAllowWhileIdle`, or `setWindow` with 10 minutes of slack when Android 12+ has not granted exact alarms. Settings shows **Allow exact reminders** until it is granted.
- `ReminderReceiver` runs `ReminderDelivery.fireDue()` and re-arms. Reminder notifications use their own high-importance **Reminders** channel. Each reminder gets its own notification id, and they ring during quiet hours because the user picked the time.
- `BootReceiver` re-arms on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. `HerApplication` re-arms at process start, and `SyncWorker` re-arms after a sync.
- `HourlyWorker` fires anything overdue before its own policy check, so a reminder the system delayed still rings on the next hourly wakeup.
