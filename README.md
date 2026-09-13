<div align="center">

# Her

**A persistent, text-only personal AI assistant for Android.**
One conversation for the life of the app. You talk; she remembers, organizes, and follows up.

[![Android CI](https://github.com/AlirezaAzadbakht/her/actions/workflows/android.yml/badge.svg)](https://github.com/AlirezaAzadbakht/her/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/AlirezaAzadbakht/her?label=release&color=blueviolet)](https://github.com/AlirezaAzadbakht/her/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
<br/>
![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white)
![Target SDK](https://img.shields.io/badge/targetSdk-36-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17-orange?logo=openjdk&logoColor=white)
![LLM](https://img.shields.io/badge/LLM-OpenAI--compatible-412991?logo=openai&logoColor=white)

[**Download**](https://github.com/AlirezaAzadbakht/her/releases/latest) ·
[**Wiki**](wiki/Home.md) ·
[**Architecture**](wiki/Architecture.md) ·
[**Scenarios**](wiki/Scenario-engine.md)

</div>

---

## What is Her?

Her is not a chat app. There is **one continuous conversation** for the life of the app, but the screen shows only **her latest message** — no scrolling thread, no history to manage.

You talk naturally. Her decides what becomes a **memory**, a **goal**, a **grocery item**, a **date**, or something she should **follow up on later**. Natural language goes in; structured records live underneath. You never need to learn tool vocabulary.

Core principles:

- **Text only.** One conversation, forever.
- **Honest.** She never claims to be human or invents a body, a room, or the weather.
- **Yours.** You bring your own OpenAI-compatible model; credentials never leave the device's Keystore.
- **Resilient.** Notes written offline queue locally and are handled together when the network returns.

## Features

| Feature | What she does |
|---|---|
| 🧠 **Memory** | Learns facts about you and recalls them by meaning when embeddings are enabled |
| 🎯 **Goals & commitments** | Tracks tasks, goals, and promises — and checks in on them |
| 🛒 **Groceries** | Maintains a shopping list from casual mentions |
| 📅 **Calendar** | Creates and reads events via the system calendar or Google Calendar |
| 🔔 **Follow-ups & briefings** | Hourly and nightly background passes plus a morning briefing, respecting quiet hours on your clock |
| 📴 **Offline queue** | Messages sent without a connection are processed in one batch later |
| ☁️ **Multi-device sync** | Every record syncs through a Google Drive `appDataFolder` change log |
| 🔎 **Web search** | Optional, when she needs current information |
| 🗄️ **Memory SQL navigator** | Inspect exactly what she has stored |
| 🔐 **Private by design** | API keys in Android Keystore-backed storage; never written to the database, never synced |

## Getting started

### Install

Grab the signed APK from the [latest release](https://github.com/AlirezaAzadbakht/her/releases/latest) and install it on an Android 10+ device.

### First run

1. Open the app. Give your name, then hers.
2. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**.
3. Continue. She greets you by those names once the connection works.
4. *Optional:* grant calendar permission, add a Google OAuth client ID for Drive/Calendar, enable web search.
5. *Optional:* Settings → Advanced → Experimental to hide the Memory SQL navigator (shown by default).

## Build from source

### Requirements

- Android Studio (or this repo's workspace toolchain)
- JDK 17
- Android SDK 36
- An OpenAI-compatible API (base URL, key, model)

### Build

A local toolchain under `.toolchain/` (gitignored) can be used:

```bash
source scripts/env.sh
./gradlew assembleDebug
```

`local.properties` should contain:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

> [!TIP]
> If `dl.google.com` is unreachable, the Gradle files already prefer Aliyun / JetBrains mirrors for Google Maven artifacts.

See [Local development](wiki/Local-development.md) for emulator and Make targets.

## Testing

**Unit tests** (run in CI):

```bash
./gradlew testDebugUnitTest
```

**Scenarios** exercise real behavior against a live LLM and are not part of CI. They read `LLM_API_BASE_URL`, `LLM_MODEL_IDENTIFIER`, and `LLM_API_SECRET_KEY` from the repo-root `.env`:

```bash
make scenarios
make scenario ID=system-calendar-create-event
```

See [Scenario engine](wiki/Scenario-engine.md).

## Architecture

```
UI → Agent runners → Tool registry → Repositories → Room
                 ↘ OpenAI-compatible LLM (streaming chat, blocking background)
Background: hourly (≤10 calls), nightly (≤50), morning briefing
Sync: Drive appDataFolder NDJSON change log (not whole-database overwrite)
```

The model only **proposes** tool calls. The app validates schemas and enforces call budgets, permissions, timestamps, and confirmation for destructive actions.

## Google integrations

Drive sync and direct Google Calendar access need an OAuth client ID from your Google Cloud project. Enter it in Settings. Each device configures API and Google credentials independently.

## CI & release

GitHub Actions runs unit tests and uploads a debug APK on every push and pull request. Pushing a version tag (`vMAJOR.MINOR.PATCH`) publishes a signed APK to a GitHub Release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

<details>
<summary><b>Setting up release signing</b></summary>

Create a keystore (keep the `.jks` file private; you need the same one for every update):

```bash
keytool -genkeypair -v \
  -keystore her-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias her
```

Then add these repository secrets (`Settings → Secrets and variables → Actions`):

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w 0 her-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias (`her` if you used the command above) |
| `RELEASE_KEY_PASSWORD` | key password |

</details>

## Documentation

| Page | What it covers |
|------|----------------|
| [Product](wiki/Product.md) | Personality, classification rules, what the user never has to say |
| [Architecture](wiki/Architecture.md) | Packages, data flow, entry points |
| [UI and flows](wiki/UI-and-flows.md) | Screens, first run, single-utterance Her, SQL Memory tab, share |
| [Agent](wiki/Agent.md) | LLM loop, tools, context, call budgets |
| [Data and sync](wiki/Data-and-sync.md) | Room, secrets, Drive, calendar, search |
| [Background work](wiki/Background-work.md) | Hourly / nightly / briefing / sync, quiet hours |
| [Local development](wiki/Local-development.md) | Toolchain, Make, emulator, tests |
| [Scenario engine](wiki/Scenario-engine.md) | Live-LLM capability pool, JSON format, how to run |
| [CI and release](wiki/CI-and-release.md) | GitHub Actions, tags, signing, published APKs |

## License

Released under the [MIT License](LICENSE). © 2026 Alireza Azadbakht
