# Architecture

Single Android module `:app`. Kotlin, Jetpack Compose, Material 3, Room, WorkManager. Manual DI in `AppGraph` — no Hilt/Dagger.

## Data flow

```
UI (Compose + HerViewModel)
  → AgentOrchestrator          chat / hourly / nightly / briefing
      → ContextBuilder         system prompt + structured snapshot + recent messages
      → LlmClient              POST {baseUrl}/chat/completions (stream for chat)
      → ToolRegistry           schema + execute
          → HerRepository      domain ↔ Room, usage, sync ops
              → HerDatabase    her.db
  ↘ background Scheduler       WorkManager
  ↘ DriveSync                  NDJSON change log in Drive appDataFolder
```

## Entry points

| Piece | File | Role |
|-------|------|------|
| `HerApplication` | `app/src/main/java/com/her/HerApplication.kt` | Builds `AppGraph`, enqueues WorkManager, provides `HerWorkerFactory` |
| `MainActivity` | `app/src/main/java/com/her/MainActivity.kt` | Compose host; `ACTION_SEND` text/plain → `consumeShare()` |
| `HerApp` | `app/src/main/java/com/her/ui/HerApp.kt` | Setup gate, then Her / optional Memory / Settings |
| `HerViewModel` | `app/src/main/java/com/her/ui/HerViewModel.kt` | UI state and agent triggers |

WorkManager’s default initializer is disabled in the manifest. The Application supplies the factory.

## Packages

| Package | Purpose |
|---------|---------|
| `com.her` | Application, activity |
| `com.her.ui.*` | Compose screens, theme, markdown, ViewModel |
| `com.her.agent.prompt` | `Identity`, `ContextBuilder` |
| `com.her.agent.runner` | `AgentOrchestrator`, `CallBudget` |
| `com.her.agent.tools` | `ToolRegistry` |
| `com.her.domain` | Models and enums (`Models.kt`) |
| `com.her.data.db` | Room entities, DAOs, FTS |
| `com.her.data.repository` | `HerRepository` |
| `com.her.data.secure` | Encrypted LLM creds, plain app settings |
| `com.her.data.remote` | LLM client, web search |
| `com.her.data.drive` | Drive NDJSON sync + `MergeEngine` |
| `com.her.data.google` | OAuth (Drive appdata + calendar readonly) |
| `com.her.data.calendar` | Device calendar via `CalendarContract.Instances`; Google Calendar API after Connect Google |
| `com.her.data.retrieval` | `HybridRanker` (FTS + lexical/recency, plus cosine similarity from `/embeddings` when enabled) |
| `com.her.core` | IDs, quiet hours, relative time, JSON helpers, secret redaction, enum aliases |
| `com.her.work` | Workers, scheduler, boot receiver |
| `com.her.notify` | Notification policy and actions |
| `com.her.di` | `AppGraph` |

## Domain statuses worth remembering

| Kind | Statuses |
|------|----------|
| Task | `OPEN`, `DONE`, `DROPPED` |
| Memory | `ACTIVE`, `HISTORICAL`, `ARCHIVED` |
| Grocery | `ACTIVE`, `PURCHASED`, `DROPPED` |
| Commitment | `OPEN`, `DONE`, `MISSED`, `DROPPED` |
| Queue | `OPEN`, `DONE`, `DROPPED` |
| Agent run | `CHAT`, `HOURLY`, `NIGHTLY`, `BRIEFING`, `CATCH_UP` |

Dropped tasks also set `deletedAt` so they leave the live lists.

## Manifest notes

`app/src/main/AndroidManifest.xml`:

- Permissions: internet, network state, notifications, boot, read/write calendar, foreground data-sync, request ignore battery optimizations
- `allowBackup="false"`
- `usesCleartextTraffic="true"` (local / custom LLM endpoints)
- Debug application id: `com.her.debug`
