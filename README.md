# Her

A text-only, persistent personal AI assistant for Android. There is one conversation for the life of the app, but the screen shows only her latest message — not a chat thread. You talk naturally. Her decides what becomes a memory, a goal, a grocery item, a date, or something she should follow up on later. Offline notes queue locally and are handled together when the network returns.

Working notes for architecture and workflows: [wiki/Home.md](wiki/Home.md).

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

## CI / release

GitHub Actions runs unit tests and uploads a debug APK on pushes and pull requests. Pushing a version tag publishes a signed APK to a GitHub Release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Create a keystore (keep the `.jks` file private; you need the same one for every update):

```bash
keytool -genkeypair -v \
  -keystore her-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias her
```

Then add these repository secrets (`Settings → Secrets and variables → Actions`):

- `RELEASE_KEYSTORE_BASE64` — `base64 -w 0 her-release.jks`
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — key alias (`her` if you used the command above)
- `RELEASE_KEY_PASSWORD` — key password

## First run

1. Open the app. Give your name, then hers.
2. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**.
3. Continue. She greets you by those names after the connection works.
4. Optional later: calendar permission, Google OAuth client ID for Drive/Calendar, web search.
5. Optional: Settings → Advanced → Experimental to hide the Memory SQL navigator (on by default).

API credentials stay in Android Keystore-backed storage on this device. They are never written to Room and never synced.

## Architecture

```
UI → Agent runners → Tool registry → Repositories → Room
                 ↘ OpenAI-compatible LLM (streaming chat, blocking background)
Background: hourly (≤10 calls), nightly (≤50), morning briefing
Sync: Drive appDataFolder NDJSON change log (not whole-database overwrite)
```

The model proposes tool calls. The app validates schemas, enforces budgets, permissions, timestamps, and destructive-action confirmation.

## Google integrations

Drive sync and direct Google Calendar access need an OAuth client ID from your Google Cloud project. Enter it in Settings. Each device configures API and Google credentials independently.
