# Data and sync

## Room

`HerDatabase` — file `her.db`, version 1 — in `app/src/main/java/com/her/data/db/Database.kt`.

Dozens of entity tables plus FTS4 on chat, short-term, and long-term memory. `HerRepository` is the only path the UI and tools should use.

Important record kinds:

| Record | Role |
|--------|------|
| `ChatMessage` | The one conversation |
| `ShortTermMemory` | Temporary context; can expire |
| `LongTermMemory` | Durable facts; shown under Memory → Information |
| `UserProfile` | Single row, id `user-profile` |
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
| `AppSettingsStore` | `her_app_settings` | Device id, quiet hours, developer mode, Google client id, web search, calendar/Drive flags, tool-call limit, last-run dates, onboarding flags |

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

`CalendarDataSource` reads/writes the Android system calendar when permissions are granted. Tools also keep Room rows (`INTERNAL` or `SYSTEM`). Google Calendar scope on the OAuth client is **readonly**.

## Web search

Off unless enabled in Settings. Default engine is DuckDuckGo Instant Answer. A custom endpoint + bearer key can be set.
