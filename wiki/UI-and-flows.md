# UI and flows

Three destinations after setup, chosen with a bottom bar in `HerApp` (not Navigation Compose routes): `Her`, `Memory`, `Settings`.

## First run

1. `HerApp` shows `SetupScreen` until LLM settings are configured **or** `apiConfiguredOnce` is true.
2. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**. Continue.
3. `testConnection()` calls `LlmClient.ping()` (a tiny chat request that should return `ok`).
4. On success: `apiConfiguredOnce = true` and `seedOnboardingIfNeeded()`:
   - Assistant: *“Hi. Before we really start, what should I call you?”*
   - Agent queue item: learn the name, then timezone and what matters — slowly, not as an interview.
5. Chat, Memory, and Settings become available.

Credentials stay in `SecureSettingsStore` (`her_secure_settings`, EncryptedSharedPreferences). They never go into Room or Drive.

If Continue does not leave setup, force-stop or reopen the app. `HerApp` reads `llmSettings()` / `settings.value` once per composition and may not recompose until something else invalidates it.

Optional later, in Settings: calendar permission, Google OAuth client ID (Drive / Calendar), web search.

## Chat (Her)

`HerScreen` is the only conversation. Send on the action button or **Enter**.

Assistant replies are markdown (`**bold**`, lists, headings). Parsing and Compose rendering are local:

- `app/src/main/java/com/her/ui/markdown/MarkdownModel.kt`
- `app/src/main/java/com/her/ui/markdown/ConversationMarkdown.kt`

No third-party markdown library.

Share (`ACTION_SEND` text/plain) lands in the same conversation via `HerViewModel.consumeShare()`.

## Memory

`MemoryScreen` is a searchable list of structured records, not a second chat.

| Section | Contents |
|---------|----------|
| **Information** | Always shown. User profile rows plus **active** long-term memories. Empty hint if she has not stored lasting facts yet. |
| **Right now** | Short-term memories that have not expired |
| People, projects, goals, tasks, commitments, open loops, routines, groceries, important dates | Live structured lists |
| Forget everything… | Destructive wipe (confirmation) |

Profile rows (name, assistant name, timezone, …) appear only after `update_user_profile` has been called. A fact told in chat appears under Information only if she actually called `remember` as long-term. Older messages are **not** backfilled.

Long-term rows can be edited or deleted from this page.

## Settings

LLM endpoint and key, quiet hours, developer mode, web search, Google client id, calendar / Drive toggles, and manual “run hourly / nightly / sync now” when developer mode is on.

Each device configures API and Google credentials independently.
