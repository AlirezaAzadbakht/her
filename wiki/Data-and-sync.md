# Data and sync

## Room

`HerDatabase` — file `her.db`, version 2 — in `app/src/main/java/com/her/data/db/Database.kt`. `MIGRATION_1_2` creates `user_understandings`.

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
| `AppSettingsStore` | `her_app_settings` | Device id, quiet hours, developer mode, Google client id, web search, calendar/Drive flags, tool-call limit, last-run dates, onboarding flags, first-run permission ask |

`redactSecrets` in `Core.kt` strips keys from debug logs.

**Never** put the API key in Room or in a Drive file.

## Retrieval

`HybridRanker` combines FTS with lexical overlap and recency. Embeddings are wired as `NoOpEmbeddingProvider` unless that flag is turned on later.

## Drive sync

`DriveSync` uploads pending `sync_ops` as NDJSON into Drive **appDataFolder** (`{deviceId}/{firstSeq}-{lastSeq}.ndjson`). It does **not** overwrite the whole database.

Download + `MergeEngine`: last-write-wins on `updatedAt`, except **`PURCHASED` grocery status wins** over a concurrent add.

Today the merge path persists remote ops for **groceries** and **chat_messages**. Other types are logged more than fully applied — treat multi-device sync as incomplete outside those.

Needs Settings: Drive enabled + Google OAuth client id. Scope: `drive.appdata`.

## Calendar

`CalendarDataSource` reads the Android system calendar through `CalendarContract.Instances` (so recurring meetings in the window show up) when Settings → Calendar access is on and `READ_CALENDAR` / `WRITE_CALENDAR` are granted. `create_calendar_event` and `update_calendar_event` write through to `CalendarContract`; `delete_calendar_event` does too after confirmation. Device events are mirrored into Room as `SYSTEM` rows with stable instance ids.

After Settings → Connect Google completes consent, `GoogleCalendarClient` reads selected calendars from the Google Calendar API (`calendar.readonly`). Those events are mirrored as `GOOGLE` rows. The API is read-only — she will not update or delete Google events. If Google is connected, Google-synced copies on the device calendar are omitted so the same meeting is not listed twice. If Google is not connected, Google-synced device events still appear as system calendar.

`get_calendar_events` can read any slice: `from` / `to` as natural phrases, ISO, epoch millis, or Jalali, or `days` from the start of `from` (or today). A date without a clock is that whole day. Named slices include `this morning`, `this afternoon`, and `this evening` / `tonight`. The window is capped at 400 days.

## Web search

Off unless enabled in Settings. When on, `web_search` calls the configured OpenAI-compatible LLM with `web_search_options` (no function tools). Location from the user profile (`country`, `timezone`) is sent when present. A custom GET endpoint + bearer key can override that path. There is no DuckDuckGo fallback.
