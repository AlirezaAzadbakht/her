# Her wiki

Her is a text-only Android assistant with **one conversation for the life of the app**. You talk naturally. She decides what becomes information, a grocery, a task, a commitment, or something to follow up on later.

This folder is the working map of the repo: what the product is, how the code is layered, and the local / CI / release workflows.

## Pages

| Page | What it covers |
|------|----------------|
| [Product](Product.md) | Personality, classification rules, what the user never has to say |
| [Architecture](Architecture.md) | Packages, data flow, entry points |
| [UI and flows](UI-and-flows.md) | Screens, first run, single-utterance Her, SQL Memory tab, share |
| [Agent](Agent.md) | LLM loop, tools, context, call budgets |
| [Data and sync](Data-and-sync.md) | Room, secrets, Drive, calendar, search |
| [Background work](Background-work.md) | Hourly / nightly / briefing / sync, quiet hours |
| [Local development](Local-development.md) | Toolchain, Make, emulator, tests |
| [Scenario engine](Scenario-engine.md) | Live-LLM capability pool, JSON format, how to run |
| [CI and release](CI-and-release.md) | GitHub Actions, tags, signing, published APKs |

## One-line architecture

```
UI → AgentOrchestrator → ToolRegistry → HerRepository → Room
                      ↘ LlmClient (OpenAI-compatible /chat/completions)
Background: hourly (≤10 calls), nightly (≤50), morning briefing (≤8)
Sync: Drive appDataFolder NDJSON change log (not a whole-database overwrite)
```

The model proposes tool calls. The app validates schemas, enforces budgets, permissions, timestamps, and confirmation for destructive actions.

## Hard product rules

- Text only. One continuous conversation.
- API keys live in Android Keystore-backed prefs. They are never written to Room and never synced.
- She must not claim to be human or invent a body, a room, or weather.
- Natural language in; structured records underneath. The person should never need tool vocabulary.

## Current shipping shape

- Package: `com.her` (debug: `com.her.debug`)
- `minSdk` 29, `compileSdk` / `targetSdk` 36, JDK 17
- Version tags: `vMAJOR.MINOR.PATCH` (CI sets `VERSION_NAME` / `VERSION_CODE` from the tag)
- Latest published tag at the time this wiki was written: **v0.1.6** → [GitHub Release](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.6)
