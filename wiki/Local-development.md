# Local development

## Toolchain

The repo is set up to run **without Android Studio**, using a gitignored tree under `.toolchain/`:

| Path | Role |
|------|------|
| `.toolchain/jdk-17` | JDK 17 |
| `.toolchain/android-sdk` | Platform 36 (and emulator pieces if you added them) |
| `.toolchain/gradle-9.6.0` | Local Gradle (avoid the wrapper re-download) |
| `.toolchain/gradle-home` | `GRADLE_USER_HOME` |
| `.toolchain/avd` | `ANDROID_AVD_HOME` — AVD name **`her`** |

`scripts/setup-toolchain.sh` downloads SDK zips from a Tencent AndroidSDK mirror (`ANDROID_SDK_MIRROR` to override) because `dl.google.com` is often unreachable. It writes `local.properties` `sdk.dir`.

Always:

```bash
source scripts/env.sh
```

That exports `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_AVD_HOME`, `GRADLE_USER_HOME`, and puts **local Gradle 9.6.0** on `PATH`.

Use `gradle …`, not `./gradlew`, on this machine. The wrapper talks to `services.gradle.org` and can hang.

Gradle already prefers Aliyun / JetBrains mirrors for Google Maven artifacts (`settings.gradle.kts`).

Do not commit `.toolchain/`, `local.properties`, `*.jks`, or APKs.

## Make

```bash
make help      # list
make apk       # always rebuild debug APK
make emulate   # boot AVD her, install if needed, launch
make stop      # adb emu kill
make scenarios # live-LLM scenario pool (needs repo-root .env)
make scenario ID=system-calendar-create-event
```

`emulate` behavior (easy to misread as a crash):

- Does **not** rebuild if `app/build/outputs/apk/debug/app-debug.apk` already exists. Use `make apk` when you changed code.
- Does **not** `adb install -r` when the on-device APK md5 already matches. A reinstall force-stops the app; that looked like a force-close.
- Starts `emulator -avd her -gpu swiftshader_indirect -accel on -no-audio` if no emulator is online.
- Launches `com.her.debug/com.her.MainActivity`.

Host needs KVM (`/dev/kvm`) for a usable emulator. If the emulator UI says “not responding,” adb often still works — click Wait or run `make emulate` again.

## Tests

```bash
source scripts/env.sh
gradle :app:testDebugUnitTest
```

All tests are JVM / Robolectric unit tests under `app/src/test/java/com/her/`. There is no `androidTest` suite.

| Test | Why it exists |
|------|----------------|
| `HerSimulationTest` | Scripted first days through real Room + tools (no live LLM). Writes `build/reports/her-simulation.html` and `.txt` under the test working directory |
| `ScenarioPoolValidationTest` | Parses `scenarios/*.json` with no LLM calls (this is what CI runs) |
| `ScenarioEngineTest` | Live-LLM pool; **not** in `testDebugUnitTest`. Run `make scenarios`. See [Scenario engine](Scenario-engine.md) |
| `MarkdownParserTest` | Local chat markdown |
| `EnumParseTest` | `cancelled` → `DROPPED` |
| `RoomDaoTest` | FTS + grocery persistence |
| `MergeEngineTest` | Drive grocery merge |
| `CallBudgetTest`, `QuietHoursTest`, `NotificationPolicyTest` | Background policy |
| `RelativeTimeParserTest`, `HybridRankerTest`, `ToolSchemaTest` | Parsers and schemas |

Robolectric does **not** download `android-all` at test time. Gradle prefetches `org.robolectric:android-all-instrumented:10-robolectric-5803371-i7` (API 29) into `app/build/robolectric-jars` and tests run with `robolectric.offline=true`. That is what keeps CI off Robolectric’s `MavenArtifactFetcher`.

## Debug identity

- Application id: `com.her.debug`
- Version name suffix: `-debug`
- Activity: `com.her.MainActivity`
