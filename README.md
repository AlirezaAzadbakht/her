# Her

A text-only, persistent personal AI assistant for Android. There is one conversation for the life of the app. You talk naturally. Her decides what becomes a memory, a goal, a grocery item, a date, or something she should follow up on later.

## Requirements

- Android Studio (or this repo's workspace toolchain)
- JDK 17
- Android SDK 36
- An OpenAI-compatible API (base URL, key, model)

## Workspace toolchain

This machine can use a local toolchain under `.toolchain/` (gitignored):

```bash
source scripts/env.sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

`local.properties` should contain:

```
sdk.dir=/absolute/path/to/android-sdk
```

If `dl.google.com` is unreachable, the Gradle files already prefer Aliyun / JetBrains mirrors for Google Maven artifacts.

## First run

1. Open the app.
2. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**.
3. Continue. Conversation onboarding starts after the connection works.
4. Optional later: calendar permission, Google OAuth client ID for Drive/Calendar, web search.

API credentials stay in Android Keystore-backed storage on this device. They are never written to Room and never synced.

## Architecture

```
UI → Agent runners → Tool registry → Repositories → Room
                 ↘ OpenAI-compatible LLM
Background: hourly (≤10 calls), nightly (≤50), morning briefing
Sync: Drive appDataFolder NDJSON change log (not whole-database overwrite)
```

The model proposes tool calls. The app validates schemas, enforces budgets, permissions, timestamps, and destructive-action confirmation.

## Google integrations

Drive sync and direct Google Calendar access need an OAuth client ID from your Google Cloud project. Enter it in Settings. Each device configures API and Google credentials independently.
