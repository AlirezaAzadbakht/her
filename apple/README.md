# Her for Apple (experimental foundation)

A native SwiftUI starting point for **iOS 17+ and macOS 14+**, alongside the existing Android app. This is a separate client and local data format, not a Kotlin/Room port or a feature-complete release.

## Implemented

- One continuous conversation, showing only the latest reply.
- OpenAI-compatible `/chat/completions`, with validated tool calls and bounded execution (8 model calls, 24 total tool calls, 8 per round).
- Structured facts, tasks, commitments, groceries, waiting-on items and decision notes. Every saved item retains an exact source quotation and source message ID.
- Searchable memory, manual completion/reopening, and explicit confirmation before forgetting. Forgetting removes all records derived from the selected source note and clears recent conversational context; other saved records and unrelated queued notes remain.
- Durable pending notes. Failed notes stay queued, with manual retry; successful record writes survive a later provider failure and deduplicate on retry using source ID, kind and evidence.
- API keys stored only in device-local Keychain, never in the snapshot. HTTPS endpoints (localhost HTTP allowed), ephemeral network session, no cross-origin redirects, no provider error body printed to the user.
- Persian/English interface, native RTL, selectable responses, keyboard send with Command+Return, stop control, system light/dark appearance and Dynamic Type on iOS.

## Deliberately not advertised as available

Google Drive synchronization/restore, Android data import, calendar access, reminders/alarms/notifications, background autonomous passes, web search, embeddings, learning roadmaps, recurrent responsibilities, emotional continuity, executive delegation dependencies and automatic updates/deletion of records through conversation. The model is told these limits. Task/commitment dates are text only; nothing rings or contacts an owner.

`waiting` is a plain evidence-backed note, **not** Android's executive delegation engine. `decision` retains a stated reason, **not** a versioned decision/reflection workflow. Replies are currently non-streaming. Unsupported providers without tool-calling support will fail visibly; the app does not silently discard its tools.

## Run on macOS

With a matching Apple Command Line Tools installation:

```sh
bash apple/scripts/check.sh
bash apple/scripts/build-macos.sh
open apple/dist/Her.app
```

The build script makes a locally ad-hoc-signed app for the current Mac architecture. It is not notarized or an App Store distribution. A signed distribution needs the contributor's own signing setup. No Apple developer identity or provider secret is included.

If the host's newest SDK requires SwiftUI macro plugins that its Command Line Tools do not contain, choose a compatible installed SDK explicitly, for example:

```sh
HER_MACOS_SDK=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk bash apple/scripts/build-macos.sh
```

Standard SwiftPM workflows also work with a healthy matching toolchain:

```sh
cd apple
swift test
swift run Her
```

`scripts/check.sh` runs the same core contract checks directly with `swiftc`, without XCTest or SwiftPM. This fallback is useful when a host's SwiftPM manifest library is out of sync with its compiler. It does not verify iOS UI or replace Xcode validation.

## Run on iPhone/iPad

1. Install full Xcode, including iOS platform support; Command Line Tools alone are insufficient.
2. Open `apple/HerApple.xcodeproj`, select the shared **HerApple** scheme and an iPhone Simulator, then Run.
3. For a physical device, choose your own unique bundle identifier and Signing Team in the target's Signing & Capabilities settings. Connect and trust your device, then Run. Signing, provisioning, TestFlight and App Store distribution are separate steps; an unsigned simulator `.app` cannot install on an iPhone.

Unsigned build checks:

```sh
xcodebuild -project apple/HerApple.xcodeproj -scheme HerApple \
  -destination 'generic/platform=macOS' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project apple/HerApple.xcodeproj -scheme HerApple \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The Apple workflow runs the core checks and both builds on a macOS runner. It uploads separate macOS and iOS Simulator apps. A green build demonstrates compilation; device interaction, screen-reader, largest Dynamic Type, landscape and lifecycle testing remain release gates.

## First use

In Settings enter both names, your provider's **base URL** (including `/v1` if required), its exact **model identifier**, and your **API key**. Save, return to the conversation, and send a short note. A blank key is allowed for local providers. No default remote endpoint is contacted.

Example inputs:

- “I prefer short replies.” → durable memory, if the provider calls the tool.
- “I should review the partnership draft.” → task.
- “I promised Sara I would send the proposal on Tuesday.” → commitment (no notification).
- “I am waiting for Mina to send the revised quote.” → waiting-on note (no outreach/monitoring).
- “We chose the smaller pilot because onboarding capacity is limited.” → decision with source evidence.

Inspect **Memory** after testing. A reply alone is not proof of a saved record. Completion and forgetting use the visible controls in Memory. The queue button opens pending notes and manual retry/discard; discarding a partially processed note does not erase any records already saved from it.

## Data and privacy

State is stored in Application Support/HerApple/state.json with owner-only file permissions, atomic writes and backup exclusion. On iOS the file has `completeUntilFirstUserAuthentication` protection. The app does not add database encryption; protect the device itself. Keychain uses `WhenUnlockedThisDeviceOnly`. There is no analytics integration.

While chatting, the selected AI provider receives your current note, recent conversation and recent/retrieved records. Keychain storage does not make those requests local. Provider retention/costs depend on your provider. Local notes are retained until explicitly forgotten; there is currently no migration/export UI or cross-device recovery. Do not use this preview as the sole copy of important records.

## Contribution boundaries and next steps

Android code and workflows are unchanged. Apple code has no third-party package dependencies. New code follows the repository's MIT license.

1. Validate the shared project on Simulator and actual iOS/macOS devices, including VoiceOver, large text, RTL editing, light/dark mode and keyboard-safe layout.
2. Add native notifications with idempotent scheduling and explicit permission handling; document iOS background scheduling limits before implementing autonomous passes.
3. Define domain and sync compatibility with Android's existing Drive operation log before claiming multi-device support. Do not sync this snapshot over the Android database.
4. Port reminder/calendar policies, explicit destructive confirmations, source-preserving corrections, recurrence and agent journal semantics, with scenario tests.
5. Extend to executive delegation, learning and emotional continuity only after preserving their source/versioning contracts.

Architecture: `Sources/HerCore` (networking, validated record tools, agent, storage); `Sources/HerApp` (shared SwiftUI and serialized queue owner). Core checks include storage corruption, durable partial writes/retry, schema/evidence rejection, unsupported destructive tools, network transport errors and budgets. They use scripted responses and URLProtocol mocks; no paid live model calls are made.
