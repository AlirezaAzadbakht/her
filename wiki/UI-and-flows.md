# UI and flows

Destinations after setup, chosen with a bottom bar in `HerApp` (not Navigation Compose routes): `Her`, optionally `Memory`, and `Settings`.

The Memory tab is an experimental read-only SQL navigator. It defaults on (`memoryTabEnabled`) and can be hidden under Settings → Advanced → Experimental.

## First run

1. `HerApp` shows `SetupScreen` until LLM settings are configured **or** `apiConfiguredOnce` is true.
2. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**. Continue.
3. `testConnection()` calls `LlmClient.ping()` (a tiny chat request that should return `ok`).
4. On success: `apiConfiguredOnce = true` and `seedOnboardingIfNeeded()`:
   - Assistant: *“Hi. Before we really start, what should I call you?”*
   - Agent queue item: learn the name, then timezone and what matters — slowly, not as an interview.
5. Her and Settings become available. Memory appears when the experimental flag is on.

Credentials stay in `SecureSettingsStore` (`her_secure_settings`, EncryptedSharedPreferences). They never go into Room or Drive.

Optional later, in Settings: calendar permission, Google OAuth client ID (Drive / Calendar), web search.

## Her (single utterance)

`HerScreen` is not a chat thread. The user never sees history and cannot scroll through past turns.

- The stage shows **only her latest message**, or the text currently streaming in.
- While she thinks, a soft bloom animation occupies the stage; the first content delta fades it out.
- The composer always enqueues. Send is never blocked by an in-flight turn.
- Offline: messages are stored as `PENDING`. A status line reads `offline · N waiting`. When the network returns, `processOutbox()` sends the whole batch as **one** turn.
- History still lives in Room and still feeds `ContextBuilder`. It is simply not displayed.

Assistant replies are markdown (`**bold**`, lists, headings). Parsing and Compose rendering are local:

- `app/src/main/java/com/her/ui/markdown/MarkdownModel.kt`
- `app/src/main/java/com/her/ui/markdown/ConversationMarkdown.kt`

Share (`ACTION_SEND` text/plain) lands in the same conversation via `HerViewModel.consumeShare()`.

## Memory (SQL navigator)

`SqlNavigatorScreen` is a read-only browser over `her.db`, not a curated memory list.

- Table list from `sqlite_master` with row counts
- Tap a table → `SELECT * FROM "<table>" ORDER BY rowid DESC LIMIT 50`, with Load more
- Free-form query box (must start with `SELECT`, `WITH`, `PRAGMA`, or `EXPLAIN`; stacked statements are rejected)
- Result grid; tap a cell to expand the full value

Writes (`DELETE`, `UPDATE`, `INSERT`, `DROP`, …) are rejected by `SqlGuard`.

## Settings

LLM endpoint and key, quiet hours, developer mode, web search, Google client id, calendar / Drive toggles, and manual “run hourly / nightly / sync now” when developer mode is on.

Advanced → Experimental: **Memory navigator (SQL)** (default on).

Each device configures API and Google credentials independently.
