# Background work

`app/src/main/java/com/her/work/Workers.kt`. `HerApplication` calls `scheduler.enqueueAll()` at process start. `BootReceiver` re-enqueues after `BOOT_COMPLETED`.

All periodic work requires a network connection.

| Worker | Unique name | When |
|--------|-------------|------|
| `HourlyWorker` | `her-hourly` | Every 1 hour |
| `NightlyWorker` | `her-nightly` | Every 1 day, first run delayed to **03:00** local |
| `BriefingWorker` | `her-briefing` | Every 1 day, first run delayed to **quiet-hours end** (default 08:00) |
| `SyncWorker` | `her-sync` | Every 6 hours; no-ops if Drive is disabled |
| `OutboxWorker` | `her-outbox` | One-shot when `PENDING` user rows exist; waits for `NetworkType.CONNECTED` |

Developer Settings can fire `runHourlyNow()`, `runNightlyNow()`, `runSyncNow()`.

## Budgets

See [Agent](Agent.md). Hourly ≤ 10 LLM calls, nightly ≤ 50, briefing ≤ 8. Chat uses the settings limit (default 12).

## Quiet hours

Default **23:30–08:00** (`QuietHours` in `Core.kt`). Overnight window: a time is “inside” if it is after start **or** before end.

`NotificationPolicy`:

- Non-urgent notifications are suppressed during quiet hours
- Duplicate key cooldown: 45 minutes
- Gap between non-urgent notifications: 20 minutes

The morning briefing is scheduled at the **end** of quiet hours, not at a fixed 8:00 if the user changed the window.

## Notifications

`app/src/main/java/com/her/notify/Notifications.kt`. Hourly’s default is silence. A user-facing message from an autonomous pass is the exception, not the loop.
