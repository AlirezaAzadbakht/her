# UI and flows

Destinations after setup, chosen with a bottom bar in `HerApp` (not Navigation Compose routes): `Her`, optionally `Memory`, and `Settings`.

The Memory tab is an experimental read-only SQL navigator. It defaults on (`memoryTabEnabled`) and can be hidden under Settings → Advanced → Experimental.

## First run

1. `HerApp` asks **What should I call you?**, then **And what should I be called?** Names go on `UserProfile`.
2. Then `SetupScreen` until `apiConfiguredOnce` is true.
3. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**. Continue.
4. `saveAndTest()` writes the credentials, then `LlmClient.ping()` (a short chat request, ~35s timeout) on a background thread. The button shows **Checking…**.
5. On success: `apiConfiguredOnce = true` and `seedOnboardingIfNeeded()`:
   - Assistant: *“Hi, {user}. I'm {her}.”*
   - Agent queue item: learn timezone and what matters — slowly, not as an interview.
6. Her and Settings become available. Memory appears when the experimental flag is on.
7. The first time that main UI appears, Android asks for calendar read/write (and notifications on API 33+). The ask is stored as `runtimePermissionsAsked` so it does not repeat. If they grant calendar, `calendarEnabled` turns on. Denial leaves the internal calendar only; Settings → Calendar access can ask again.

Credentials stay in `SecureSettingsStore` (`her_secure_settings`, EncryptedSharedPreferences). They never go into Room or Drive.

Optional later, in Settings: Calendar access (requests permission if needed), Google OAuth client ID (Drive / Calendar), web search. **Connect Google** launches the Play Services consent `pendingIntent` when needed, then authorizes again so Drive and the Google Calendar API have a token.

## Her (single utterance)

`HerScreen` is not a chat thread. The user never sees history and cannot scroll through past turns.

- The stage shows **only her latest message**, or the text currently streaming in.
- Replies sit in the middle of the stage (centered lines, vertically centered while they fit). Incoming tokens are revealed one grapheme at a time at the caret so they land in place instead of arriving as a chunk; once the reply is taller than the stage, it scrolls and follows the bottom unless the user scrolls up.
- While she thinks (no text yet), three quiet dots breathe in the middle of the stage.
- Partial text stays on screen during tool rounds. Streamed text is held until Room’s latest assistant row catches up, so the reply does not flash back to the previous message.
- Under her reply, a quiet line (`ReceiptLine`) lists what she saved that turn, such as `added rice to groceries · undo`. Undo reverses records she just created and marks the receipt `undone`. The line hides while she is thinking or streaming. Settings → Advanced → Experimental → **Show what she saved** turns it off.
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

LLM endpoint and key, quiet hours (including **Hourly during quiet hours**, default off, and **Allow background work** when the OS is still battery-optimizing the app), developer mode, web search (uses that same Base URL / key / model; optional custom search endpoint), Google client id, calendar / Drive toggles (Drive is labeled experimental: it syncs groceries and the conversation only), and manual “run hourly / nightly / sync now” when developer mode is on.

Advanced → Experimental: **Memory navigator (SQL)** (default on), **Show what she saved** (default on).

Each device configures API and Google credentials independently.
