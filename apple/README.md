# Her for iOS and macOS

Native SwiftUI client for **iOS 17+ and macOS 14+**, alongside Android. Version 0.2.0 adds calendar, reminders, autonomous review and the Android-compatible Drive change log. This remains a development build: compilation/mocked contracts are separate from real-device permissions, delivery and live Google interoperability.

## Capabilities

| Capability | Implementation and boundary |
|---|---|
| Conversation | Latest reply, source-backed records, queued retry, bounded tools and Keychain credentials. No web browsing or outreach. |
| System calendar | EventKit reads recurring instances; create/update on a writable calendar; overlap rejection; delete one occurrence only after native confirmation, expiring in 15 minutes. |
| Internal calendar | Explicit INTERNAL target; Android-compatible rows; create/update/delete and cross-device merge. OS/Google calendar mirrors never enter Drive sync. |
| Google calendar | Read-only selected/primary calendars, pagination and recurring instances. Requires maintainer OAuth setup and consent. |
| Timed reminders | UserNotifications independent of model/network; move/cancel; daily/weekly/monthly repeat; completion and one-hour snooze actions. Only the creator device schedules delivery. |
| Conditional reminders | Linked existing task/commitment; cancel when locally completed. Remote changes take effect after the creator syncs. |
| Person/place reminders | Conversational triggers, no invented time or location tracking. Current user evidence of the encounter is required to mark fulfilled. |
| Automatic review | Opt-in and silent by default; hourly eligibility, quiet hours, catch-up, morning briefing and quiet nightly review. One read-only model call per pass; no autonomous record/calendar mutation or outreach. |
| Background runtime | iOS BGAppRefresh/BGProcessing opportunities plus foreground catch-up. macOS scheduler while running and optional user-installed launch agent while closed. |
| Sync | Android's Google Drive appDataFolder NDJSON protocol, paginated files, bounded upload batches, cursors, LWW conflicts, tombstones and concurrent purchased-grocery handling. |
| Other Android domains | Opaque rows are preserved, not executed. Android learning, emotional, recurrence and executive-delegation engines are not ported. |

Persian/English UI, RTL, light/dark appearance, selectable text, stop control and Command+Return sending are included.

## First use

1. Set names, OpenAI-compatible base URL (including /v1 when needed), exact model and API key in Settings.
2. Grant notifications on the first timed-reminder request, or in Reminders.
3. Grant system calendar access in Calendar. Ask in chat to create/move an event. Explicit INTERNAL events do not need OS calendar permission.
4. Enable follow-ups and save quiet hours in Settings. Reviews can spend model credit without an active chat.
5. On macOS, put Her.app in a stable location before enabling closed-app work. Its login job uses that executable path. Remove the job before moving/deleting the app.
6. Google connection requires a build configured by **the owner of Android's Google Cloud project**. See [GOOGLE_SETUP.md](GOOGLE_SETUP.md). An unrelated project cannot read Android's private appDataFolder.

Examples: “Remind me tomorrow at 9 to send the proposal”; “Remind me every Monday at 10”; “Only remind me if the proposal task is still open”; “Next time I am with Sara, remind me to ask about the quote”; “What are my meetings next Tuesday?”; “Keep this meeting in Her's internal calendar so it syncs.”

Inspect saved records and calendar/reminder status; a reply alone is not a receipt. Device tool timestamps require an offset. Ambiguous times require clarification. Jalali conversion currently depends on the chosen model; tools validate the resulting timestamp rather than the original natural-language interpretation.

System event writes retain a small Her receipt marker in event notes to recover interrupted writes. Recurring event references include their original occurrence time; updates/deletes resolve that exact instance rather than EventKit's first occurrence.

## Background and delivery limits

iOS does **not** guarantee hourly/exact-time background model or sync execution. earliestBeginDate is only a lower bound. Force quit, low power, usage patterns and system discretion can defer work. Clock notifications already submitted do not wait for these passes or the network. Permission, Focus, power and system policy can still affect presentation; this is not an alarm-clock guarantee.

Recurring notifications use the native repeat trigger when its first match is not before the requested start. A series starting later uses a rolling set of up to eight absolute occurrences, subject to capacity, until a later wake can safely install the native repeat. It never intentionally rings before the requested first occurrence. Capacity failures are visible.

The optional macOS login job attempts a wake every 15 minutes; policy gates hourly reviews and six-hour sync. Sleep is not bypassed and Keychain must be accessible. UI and helper processes share an exclusive lock over whole runs.

Quiet hours suppress proactive notices; explicit timed reminders retain the chosen time. The nightly pass is a quiet read-only review, not Android's full consolidation/emotional engine. Successful passes are stamped only after success; repeated/near-duplicate notices are suppressed.

## Sync semantics

- Wire fields match Android: id, entityType, entityId, opType, payloadJson (JSON string), createdAt (epoch milliseconds), deviceId and seq.
- Files are named deviceId/firstSeq-lastSeq.ndjson in appDataFolder.
- Acknowledgements/cursors persist atomically. Malformed, mismatched or gapped logs stop without cursor advancement.
- Tombstones prevent resurrection. Concurrent grocery purchase wins over add. Learning/companion documents merge atomically; unknown properties survive.
- Typed projections cover memories, tasks, commitments, groceries, waiting-on items, profile names, reminders, chat and internal calendar. Other JSON is preserved without implementing its tools.
- Remote pending chat is history, never a local outbox request. Unchanged imported rows do not echo. Forgetting establishes a conversation boundary; tombstones and Android FORGOTTEN/source-dependency links suppress late descendants from projection and model context.
- API/Google credentials, OS/Google calendar mirrors, permissions, notification action receipts and device review settings never sync.
- The creator owns reminder delivery. A remote change cannot affect that device before it syncs.
- A local profile is bound to its Google account. Another account is rejected to prevent cross-account data disclosure.
- Mock transport/compatibility tests are included. Live Android–Apple interoperability still requires maintainer OAuth setup and real consent.

## Build and verify

Full app with official Google Sign-In 9.0.0 and Xcode:

    xcodebuild -project apple/HerApple.xcodeproj -scheme HerApple \
      -destination 'generic/platform=macOS' CODE_SIGNING_ALLOWED=NO build
    xcodebuild -project apple/HerApple.xcodeproj -scheme HerApple \
      -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
    xcodebuild -project apple/HerApple.xcodeproj -scheme HerApple \
      -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build

Choose the registered bundle identifier and Signing Team for a physical iPhone. Unsigned device/simulator apps are not installable signed IPAs. TestFlight/App Store needs the owner's signing/provisioning setup.

Core contracts need no paid model, Google account, calendar or notification consent:

    bash apple/scripts/check.sh
    # With a healthy matching Xcode/SwiftPM toolchain:
    cd apple
    swift test

Direct compiler build (native/local features, without Google SDK):

    bash apple/scripts/build-macos.sh
    # If the host's newest SDK/SwiftUI plugins are mismatched:
    HER_MACOS_SDK=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk \
      bash apple/scripts/build-macos.sh

The direct build is locally ad-hoc signed, not notarized. Its Google path requires the full Xcode SDK build.

The [Apple workflow](../.github/workflows/apple.yml) runs core checks, builds macOS/Simulator/unsigned iOS-device targets, launches an empty first-run Simulator app and uploads artifacts/screenshot. This is not real-device calendar/notification or live Google validation. Release gates include VoiceOver, large text, RTL editing, landscape, timezone/DST, locked-device, force-quit and notification-action tests.

## Data and architecture

HerCore contains models, validated tools, sync, dates and policy. HerApp contains EventKit/UserNotifications, Google SDK, UI and background coordination. Google SDK is pinned at 9.0.0; Android code is unchanged.

State stays in Application Support/HerApple/state.json with atomic writes, owner-only access and backup exclusion. Earlier snapshots migrate. iOS protection is completeUntilFirstUserAuthentication; there is no extra database encryption. API Keychain access is WhenUnlockedThisDeviceOnly; Google manages its tokens in its SDK. Notification actions are durably queued and deduplicated locally.

The chosen model receives conversation and relevant memory/calendar during chat/review. Keychain does not make processing local. Google sync uploads the operation log to the user's appDataFolder. No analytics or hidden server is included. There is no cross-account reset/export UI; keep another copy of important records.
