# Data and sync

## Room

`HerDatabase` — file `her.db`, version 9 — in `app/src/main/java/com/her/data/db/Database.kt`. `MIGRATION_1_2` creates `user_understandings`; `MIGRATION_2_3` creates the `memory_embeddings` vector cache; 3→4 adds reminders, 4→5 feelings, 5→6 learning records, 6→7 companion records, 7→8 the separate `wondering` table, and 8→9 adds `reminders.alarm`, `api_usage.cachedInputTokens` / `backgroundTokens`, and `tasks.googleTaskId`.

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
| `SyncOp` | Change-log row for Drive |
| `PendingConfirmation` | Destructive-action gate |
| `AgentRun` | Telemetry |

## Secrets vs settings

| Store | Prefs name | What |
|-------|------------|------|
| `SecureSettingsStore` | `her_secure_settings` | LLM base URL, API key, model (EncryptedSharedPreferences + MasterKey) |
| `AppSettingsStore` | `her_app_settings` | Device id, quiet hours, developer mode, web search, calendar/Drive flags, tool-call limit, last-run dates, onboarding flags, first-run permission ask |

`redactSecrets` in `Core.kt` strips keys from debug logs.

**Never** put the API key in Room or in a Drive file.

## Retrieval

`HybridRanker` combines FTS with lexical overlap, importance, confidence, and recency. With Settings → Embeddings on, it also blends in cosine similarity from the configured `/embeddings` model; see [Agent](Agent.md#retrieval). The `memory_embeddings` vector cache is device-local and never synced.

## Drive sync

`DriveSync` uploads pending `sync_ops` as NDJSON into Drive **appDataFolder** (`{deviceId}/{firstSeq}-{lastSeq}.ndjson`). It does **not** overwrite the whole database.

Download + `MergeEngine`: last-write-wins per field on `updatedAt`, except **`PURCHASED` grocery status wins** over a concurrent add. A delete sets `deletedAt` and stays deleted.

`SyncCodec` (`app/src/main/java/com/her/data/drive/SyncCodec.kt`) reads the local row and writes the merged row back for every synced table: chat messages, short- and long-term memories, profile, user understandings, people, projects, goals, tasks, commitments, open loops, routines, groceries, important dates, recurring responsibilities, internal calendar events, memory relationships, agent state, and agent queue.

- Rows applied from another device are written inside `HerRepository.applyingRemote { }`, so they are not queued and uploaded back.
- Device and Google calendar rows are local mirrors. They are never enqueued, and remote ones are ignored; each phone mirrors its own sources.
- A remote `PENDING` chat row is stored as `SENT`, so another device's outbox never runs it here.
- Files are listed across all Drive pages and read oldest first per device. The per-device cursor comes from the `{firstSeq}-{lastSeq}` file name, so a file already read is not downloaded again.
- A row that cannot be applied (for example, a delete for a row this device never had) is skipped and logged, and the sync continues.

Needs Settings: Drive enabled + Connect Google. Scope: `drive.appdata`. A first-run **Restore from Google Drive** uses the same download path: one `sync` after Drive consent, then the restored profile can skip the name steps. API keys never sync.

## Calendar

`CalendarDataSource` reads the Android system calendar through `CalendarContract.Instances` (so recurring meetings in the window show up) when Settings → Calendar access is on and `READ_CALENDAR` / `WRITE_CALENDAR` are granted. `create_calendar_event` and `update_calendar_event` write through to `CalendarContract`; `delete_calendar_event` does too after confirmation. Device events are mirrored into Room as `SYSTEM` rows with stable instance ids.

After Settings → Connect Google completes consent, `GoogleCalendarClient` reads selected calendars from the Google Calendar API (`calendar.readonly`). Those events are mirrored as `GOOGLE` rows. The API is read-only — she will not update or delete Google events. If Google is connected, Google-synced copies on the device calendar are omitted so the same meeting is not listed twice. If Google is not connected, Google-synced device events still appear as system calendar.

## Google Tasks

With Settings → Connections → **Google Tasks** on, `GoogleTasksSync` (`app/src/main/java/com/her/data/google/`) mirrors her tasks with a list named **Her** in Google Tasks, scope `tasks`. It runs on every hourly wake and every sync, and makes no model calls.

- **Pull first.** Changes since `lastTasksSyncAt`, including completed and deleted ones. The newer side wins by `updated` / `updatedAt`. Completed in Google means DONE here, deleted means DROPPED. A task added in Google arrives with id `gtask-<googleId>`, so every device that pulls it lands on the same row.
- **Push.** Local tasks changed since the last sync. A task without `googleTaskId` is sent only by the device that created it, so two phones do not both add it; its new id syncs through Drive. Mapped tasks are patched, and DROPPED or deleted ones are deleted there.
- Google keeps only the date of `due`. A date that changed in Google comes back as 23:59 that day in the profile timezone; an unchanged date keeps her time.

`get_calendar_events` can read any slice: `from` / `to` as natural phrases, ISO, epoch millis, or Jalali, or `days` from the start of `from` (or today). A date without a clock is that whole day. Named slices include `this morning`, `this afternoon`, and `this evening` / `tonight`. The window is capped at 400 days.

## Web search

Off unless enabled in Settings. When on, `web_search` calls the configured OpenAI-compatible LLM with `web_search_options` (no function tools). Location from the user profile (`country`, `timezone`) is sent when present. A custom GET endpoint + bearer key can override that path. There is no DuckDuckGo fallback.

Companion records use atomic document merge and deletion-wins tombstones rather than per-field merge. See [Ongoing support](Ongoing-support.md) for provenance, bounded retrieval, forgetting cascades and preference generations.

See [Personal support](Personal-support.md) for the extensions to memory, planning, recurring responsibilities, decisions and situation briefings, including sync and notification limitations.
