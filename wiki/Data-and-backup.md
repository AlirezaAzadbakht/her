# Data and backup

## Room

`HerDatabase` — file `her.db`, version 11 — in `app/src/main/java/com/her/data/db/Database.kt`. `MIGRATION_1_2` creates `user_understandings`; `MIGRATION_2_3` creates the `memory_embeddings` vector cache; 3→4 adds reminders, 4→5 feelings, 5→6 learning records, 6→7 companion records, 7→8 the separate `wondering` table, 8→9 adds `reminders.alarm`, `api_usage.cachedInputTokens` / `backgroundTokens`, and `tasks.googleTaskId`, and 9→10 removes what Drive sync and Google left behind: it drops `sync_ops` and `sync_cursors`, deletes `GOOGLE` calendar mirrors, and rebuilds `tasks` without `googleTaskId` (SQLite on minSdk 29 has no `DROP COLUMN`). 10→11 creates `persona_traits`; its rows are seeded from her name on first read ([Personality](Personality.md)).

Dozens of entity tables plus FTS4 on chat, short-term, and long-term memory. `HerRepository` is the only path the UI and tools should use.

Important record kinds:

| Record | Role |
|--------|------|
| `ChatMessage` | The one conversation |
| `ShortTermMemory` | Temporary context; can expire |
| `LongTermMemory` | Durable facts; inspectable via the Memory SQL navigator |
| `UserProfile` | Single row, id `user-profile` |
| `UserUnderstanding` | Living picture of this person; always in context; one ACTIVE row per facet |
| People, projects, goals, tasks, commitments, open loops | Structured life |
| Groceries, routines, important dates, responsibilities | Household / repeating |
| `AgentQueueItem`, `AgentStateEntry` | Private assistant todos and notes |
| `CalendarEvent` | Internal and system events |
| `PendingConfirmation` | Destructive-action gate |
| `AgentRun` | Telemetry |

## Secrets vs settings

| Store | Prefs name | What |
|-------|------------|------|
| `SecureSettingsStore` | `her_secure_settings` | LLM base URL, API key, model (EncryptedSharedPreferences + MasterKey) |
| `AppSettingsStore` | `her_app_settings` | Device id, quiet hours, developer mode, web search, calendar flag, tool-call limit, last-run dates, onboarding flags, first-run permission ask |

`redactSecrets` in `Core.kt` strips keys from debug logs.

**Never** put the API key in Room or in a backup file.

## Retrieval

`HybridRanker` combines FTS with lexical overlap, importance, confidence, and recency. With Settings → Embeddings on, it also blends in cosine similarity from the configured `/embeddings` model; see [Agent](Agent.md#retrieval). The `memory_embeddings` vector cache goes into a backup with everything else; it is only a cache and rebuilds as she searches.

## Backup

Settings → **Backup** and the first-run **Restore from a backup** use `Backup` (`app/src/main/java/com/her/data/backup/Backup.kt`). A backup is a zip with two entries:

- `her.db`: the Room file. Export runs `PRAGMA wal_checkpoint(TRUNCATE)`, then copies the file inside `runInTransaction`, so no writer or checkpoint changes it mid-copy.
- `settings.json`: the portable part of `AppSettings` (`toBackupJson` / `withBackup`). That is quiet hours, web search and embeddings toggles and models, tool-call limit, Memory tab, receipts, background model and cap, and `deviceId`. The device id comes along because reminders and schedules ring only on the device that owns them. API keys, calendar access (a per-phone permission), run bookkeeping, and onboarding flags stay out.

**Save backup** writes through the system file picker (`CreateDocument`), and **Share** writes `cache/backup/` and hands it out through a `FileProvider`.

Import never touches the live database directly:

1. `Backup.stage` reads only the two known entry names into `restore.db` and `files/restore-settings.json`.
2. It opens `restore.db` raw and runs `PRAGMA quick_check`, then compares `user_version` with `DB_VERSION`. A newer backup is refused with "Update the app first." This runs before Room sees the file, because Room's default error handler would swap a corrupt file for an empty database.
3. It opens `restore.db` with `HerDatabase.build`, the same migrations and schema check the app uses, so an old backup is migrated now.
4. Any failure deletes the staged files and shows a message. On success the app restarts, and `AppGraph` calls `Backup.applyStaged` before building Room, which deletes `her.db` and its WAL and renames `restore.db` into place.

## Calendar

`CalendarDataSource` reads the Android system calendar through `CalendarContract.Instances` (so recurring meetings in the window show up) when Settings → Calendar access is on and `READ_CALENDAR` / `WRITE_CALENDAR` are granted. `create_calendar_event` and `update_calendar_event` write through to `CalendarContract`; `delete_calendar_event` does too after confirmation. Device events are mirrored into Room as `SYSTEM` rows with stable instance ids.

Google Calendar events that sync to the phone's calendar show up here like any other device event.

`get_calendar_events` can read any slice: `from` / `to` as natural phrases, ISO, epoch millis, or Jalali, or `days` from the start of `from` (or today). A date without a clock is that whole day. Named slices include `this morning`, `this afternoon`, and `this evening` / `tonight`. The window is capped at 400 days.

## Web search

Off unless enabled in Settings. When on, `web_search` calls the configured OpenAI-compatible LLM with `web_search_options` (no function tools). Location from the user profile (`country`, `timezone`) is sent when present. A custom GET endpoint + bearer key can override that path. There is no DuckDuckGo fallback.

The same switch offers `fetch_url`, which GETs one `http`/`https` page the person shared or named, straight from the phone (`PageFetcher.kt`). It resolves the host first and refuses loopback, link-local and private addresses, on every redirect hop too; reads at most 1 MB; strips the HTML to text; and labels the result as data, not instructions.

See [Ongoing support](Ongoing-support.md) for provenance, bounded retrieval, forgetting cascades and preference generations.

See [Personal support](Personal-support.md) for the extensions to memory, planning, recurring responsibilities, decisions and situation briefings, including notification limitations.
