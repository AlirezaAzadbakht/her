# UI and flows

`HerApp` moves between four stages with a fade-through: **Loading**, **Names**, **Setup**, and **Main**. On API 31+ the leaving stage also softly blurs. Her presence orb is a shared element (`Modifier.sharedPresence()`), so it glides from one stage's slot to the next instead of cutting.

In Main, a floating glass nav picks the destination (not Navigation Compose routes): `Her`, optionally `Memory`, and `Settings`.
- A glow indicator slides between the labels.
- Each tab change gives a light haptic tick.
- Tabs slide in from the side they sit on.
- The nav tucks away while the keyboard is up.

The Memory tab is an experimental read-only SQL navigator. It defaults on (`memoryTabEnabled`) and can be hidden under Settings → Advanced → Experimental.

## Look and motion

The look is warm glow. A deep warm night palette applies in dark mode and a dawn palette in light mode, following the system. Tokens live in `ui/theme/Theme.kt`, including `HerColors` for glow, glass, grain, and orb ink.
- **Background:** `AmbientBackground` paints the ground, two slow drifting warm lights, and faint film grain. The grain is an AGSL shader on API 33+ and a tiled noise bitmap below that.
- **Motion tokens:** every animation uses `ui/theme/Motion.kt`: durations, emphasized easings, and gentle/snappy springs.
- **Reduced motion:** when the system animator scale is 0, `LocalReducedMotion` freezes ambient drift, halos, and orbs on a still frame.
- **Haptics:** `ui/theme/Haptics.kt` names each moment: send (confirm), her first words (soft), tab change and buttons (tick), toggles, and errors (reject).
- **Components:** reusable pieces live in `ui/components/`: `GlassPill`, `HerButton` (label → loading orb → drawn check), `HerTextField` (warming label and a gradient underline that grows from the centre), `HerSwitch`, `SectionCard`, `ShimmerText`, `SkeletonRows`, and `StaggeredText`.

### Loaders

Loading states are [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs) by Jakub Antalik (MIT). The engine is transcribed to Kotlin in `ui/orbs/` from its SwiftUI port, and `OrbGoldenTest` checks it against the upstream golden vectors. Details are in `third_party/thinking-orbs/README.md`.

How the orbs work:
- `ThinkingOrb` draws a frame on a `Canvas` in Her's warm ink.
- All orbs share one clock (`ProvideOrbClock`), which only ticks while the app is resumed and some orb is animating.
- `HerPresence` adds a breathing halo, an AGSL shader on API 33+ with a gradient below that.

An orb only shows while real work is in flight. Fast reads get a 150ms grace period, so they never flash a loader.

| Moment | Orb |
|---|---|
| She is thinking, no words yet | `breathing`, large, centre stage |
| Her reply is streaming | `composing`, small, above the reply |
| Idle with a reply shown | `breathing`, small, still |
| You are typing | `listening`, small |
| Loading, before the profile row arrives | `breathing` |
| Name steps and setup | `shaping` |
| Setup "Checking…", Save & test, Connect Google | `connecting`, inside the button |
| Memory tables loading or a query running | skeleton rows / `searching` |
| Offline with messages waiting | `weaving`, in the status chip |

### Splash and icons

- **Splash:** `Theme.Her.Starting` (core-splashscreen) shows an animated dotted ring on API 31+ and a static one below. It holds until the profile row is known, then the icon swells away as the splash fades into the Loading orb.
- **Launcher icon:** an adaptive icon (`mipmap-anydpi/ic_launcher.xml`) with a monochrome layer for themed icons.
- **Notifications:** they use the monochrome `ic_notification` tinted with `her_accent`.

## First run

1. `HerApp` asks **What should I call you?**, then **And what should I be called?** Names go on `UserProfile`. Each greeting inks in word by word beside the `shaping` orb, and the field eases up once the question has been said.
2. Then `SetupScreen` until `apiConfiguredOnce` is true.
3. Enter an OpenAI-compatible **Base URL**, **API key**, and **Model**. Continue.
4. `saveAndTest()` writes the credentials, then runs `LlmClient.ping()` (a short chat request with a ~35s timeout) on a background thread. The button becomes a `connecting` orb with a shimmering **Checking…**. On failure, the fields shake, a reject haptic plays, and the message slides in.
5. On success, the button draws a check, the halo flares, `apiConfiguredOnce = true`, and `seedOnboardingIfNeeded()` runs:
   - Assistant: *“Hi, {user}. I'm {her}.”*
   - Agent queue item: learn timezone and what matters — slowly, not as an interview.
6. Her and Settings become available. Memory appears when the experimental flag is on.
7. The first time that main UI appears, Android asks for calendar read/write, and for notifications on API 33+.
   - The ask is stored as `runtimePermissionsAsked` so it does not repeat.
   - If they grant calendar, `calendarEnabled` turns on.
   - Denial leaves the internal calendar only; Settings → Calendar access can ask again.

Credentials stay in `SecureSettingsStore` (`her_secure_settings`, EncryptedSharedPreferences). They never go into Room or Drive.

Optional later, in Settings: Calendar access (requests permission if needed), Google OAuth client ID (Drive / Calendar), web search. **Connect Google** launches the Play Services consent `pendingIntent` when needed, then authorizes again so Drive and the Google Calendar API have a token.

## Her (single utterance)

`HerScreen` is not a chat thread. The user never sees history and cannot scroll through past turns.

- The stage shows **only her latest message**, or the text currently streaming in.
- Replies sit in the middle of the stage (centered lines, vertically centered while they fit).
  - Incoming tokens are revealed one grapheme at a time at a breathing caret, so they land in place instead of arriving as a chunk.
  - The newest dozen graphemes ink in gradually (`RevealTail`), then settle once the reply is whole.
  - Once the reply is taller than the stage, it scrolls and follows the bottom unless the user scrolls up.
- While she thinks (no text yet), a large `breathing` orb fills the centre of the stage. When her first words land, it shrinks into a small orb above the reply and a soft haptic plays.
- When a new turn starts, the previous reply drifts up and fades out.
- Partial text stays on screen during tool rounds. Streamed text is held until Room’s latest assistant row catches up, so the reply does not flash back to the previous message.
- Under her reply, a quiet line (`ReceiptLine`) lists what she saved that turn, such as `added rice to groceries · undo`.
  - Lines rise in one after another.
  - Undo reverses records she just created, strikes the line through, and marks the receipt `undone`.
  - The line hides while she is thinking or streaming.
  - Settings → Advanced → Experimental → **Show what she saved** turns it off.
- The composer is a glass pill that warms when focused.
  - The send button springs in once there is text.
  - On send, the text floats up and fades, and a confirm haptic plays.
  - The composer always enqueues; send is never blocked by an in-flight turn.
- **Offline:** messages are stored as `PENDING`, and a status chip reads `offline · N waiting` beside a `weaving` orb. When the network returns, `processOutbox()` sends the whole batch as **one** turn.
- **Send errors:** they appear as a chip that shakes once.
- History still lives in Room and still feeds `ContextBuilder`. It is simply not displayed.

Assistant replies are markdown (`**bold**`, lists, headings). Parsing and Compose rendering are local:

- `app/src/main/java/com/her/ui/markdown/MarkdownModel.kt`
- `app/src/main/java/com/her/ui/markdown/ConversationMarkdown.kt`

Share (`ACTION_SEND` text/plain) lands in the same conversation via `HerViewModel.consumeShare()`.

## Memory (SQL navigator)

`SqlNavigatorScreen` is a read-only browser over `her.db`, not a curated memory list.

- Table list from `sqlite_master` with row-count badges. Skeleton rows show if loading is slow.
- Tap a table → `SELECT * FROM "<table>" ORDER BY rowid DESC LIMIT 50`, with Load more.
- Free-form query card (must start with `SELECT`, `WITH`, `PRAGMA`, or `EXPLAIN`; stacked statements are rejected). The Run button shows a `searching` orb while a query runs.
- Result grid with alternating rows; the first rows rise in. Tap a cell to open the full value in a bottom sheet.
- Errors show in a card that shakes once.

Writes (`DELETE`, `UPDATE`, `INSERT`, `DROP`, …) are rejected by `SqlGuard`.

## Settings

Settings is grouped into glass cards that rise in on first open:

- **Her mind:** LLM endpoint, key, and model. **Save & test** shows the connecting orb, then a drawn check.
- **Quiet hours:** two time chips that open a 24-hour time picker and save on confirm.
  - **Hourly during quiet hours** (default off).
  - **Allow background work** while the OS is still battery-optimizing the app.
- **Connections:** calendar, Drive sync, web search (same Base URL / key / model; optional custom search endpoint), embeddings, Google client ID, and **Connect Google**.
- **Today:** API usage numbers that count up.
- **Advanced:** expands with a turning chevron.
  - **Experimental:** **Memory navigator (SQL)** (default on) and **Show what she saved** (default on).
  - **Developer:** developer mode, and manual run hourly / nightly / sync now.
  - Activity, agent runs, and debug logs.

The footer links to GitHub and credits thinking-orbs.

Each device configures API and Google credentials independently.
