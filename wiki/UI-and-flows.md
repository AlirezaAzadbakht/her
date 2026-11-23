# UI and flows

`HerApp` moves between four stages with a fade-through: **Loading**, **Names**, **Setup**, and **Main**. On API 31+ the leaving stage also softly blurs. Her presence orb is a shared element (`Modifier.sharedPresence()`), so it glides from one stage's slot to the next instead of cutting.

In Main, a floating glass nav picks the destination (not Navigation Compose routes): `Her`, optionally `Memory`, and `Settings`.
- A glow indicator slides between the labels.
- Each tab change gives a light haptic tick.
- Tabs slide in from the side they sit on.
- The nav tucks away while the keyboard is up.

The Memory tab shows what she knows (counts, topics, people), what she has recently learnt, how she feels, and, under Advanced, a read-only SQL navigator. It is experimental, defaults on (`memoryTabEnabled`), and can be hidden under Settings → Advanced → Experimental.

## Look and motion

The look is warm glow. A deep warm night palette applies in dark mode and a dawn palette in light mode, following the system. Tokens live in `ui/theme/Theme.kt`, including `HerColors` for glow, glass, grain, and orb ink.
- **Background:** `AmbientBackground` paints the ground, two slow drifting warm lights, and faint film grain. The grain is an AGSL shader on API 33+ and a tiled noise bitmap below that.
- **Motion tokens:** every animation uses `ui/theme/Motion.kt`: durations, emphasized easings, and gentle/snappy springs.
- **Reduced motion:** when the system animator scale is 0, `LocalReducedMotion` freezes ambient drift, halos, and orbs on a still frame.
- **Haptics:** `ui/theme/Haptics.kt` names each moment: send (confirm), her first words (soft), tab change and buttons (tick), toggles, and errors (reject).
- **Components:** reusable pieces live in `ui/components/`: `GlassPill`, `HerButton` (label → loading orb → drawn check), `HerTextField` (warming label and a gradient underline that grows from the centre), `HerSwitch`, `NoteWithDetails`, `SectionCard`, `ShimmerText`, `SkeletonRows`, and `StaggeredText`.

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
| Setup "Checking…", Save & test | `connecting`, inside the button |
| Memory tables loading or a query running | skeleton rows / `searching` |
| Offline with messages waiting | `weaving`, in the status chip |

### Splash and icons

- **Splash:** `Theme.Her.Starting` (core-splashscreen) shows an animated dotted ring on API 31+ and a static one below. It holds until the profile row is known, then the icon swells away as the splash fades into the Loading orb.
- **Launcher icon:** an adaptive icon (`mipmap-anydpi/ic_launcher.xml`) with a monochrome layer for themed icons.
- **Notifications:** they use the monochrome `ic_notification` tinted with `her_accent`.

## First run

1. `HerApp` asks **What should I call you?**, then **And what should I be called?** Names go on `UserProfile`. Each greeting inks in word by word beside the `shaping` orb, and the field eases up once the question has been said.
2. On the first name screen they can instead tap **Restore from a backup** and pick a backup zip (see [Data and backup](Data-and-backup.md#backup)). If the file is checked and staged, the app restarts on the restored data; both names come back, so HerApp skips the rest of this step and opens the API form. A file that isn't a backup, is damaged, or comes from a newer version shows a message on that screen and changes nothing.
3. Then `SetupScreen` until `apiConfiguredOnce` is true.
4. Connect a model. `HerCloudConnect` (also in Settings → Her mind) comes first: a large **Scan QR code** card, then **Load QR image** and **Paste settings**, then a **Get credit on Her Cloud** link. Under it, the `Disclosure` row **Use my own API endpoint** folds away the OpenAI-compatible **Base URL**, **API key**, **Model** and **Continue**; it opens when tapped, or by itself when a check fails.
   - The link opens [Her Cloud](https://cloud.her-project.ir/) to buy credit. **Scan QR code** opens the ZXing scanner (it asks for the camera) and **Paste settings** reads the clipboard. **Load QR image** opens the photo picker (no permission needed), reads every QR in the picture with ZXing's `QRCodeMultiReader` (`qrTexts`), and keeps the first that parses, so the Cafe Bazaar link on Her Cloud's cards is skipped. All three take Her Cloud's `{base_url, model_name, api_key}` JSON through `LlmSettings.fromJson`, fill the fields, and run `saveAndTest()` at once. Anything else shows "That isn't Her Cloud settings."
5. `saveAndTest()` writes the credentials, then runs `LlmClient.ping()` (a short chat request with a ~35s timeout) on a background thread. The button becomes a `connecting` orb with a shimmering **Checking…**. On failure, the fields shake, a reject haptic plays, and a plain sentence from `AgentOrchestrator.failureOf` slides in, with the redacted raw error behind **Details**.
6. On success, the button draws a check, the halo flares, `apiConfiguredOnce = true`, and `seedOnboardingIfNeeded()` runs:
   - Assistant: *“Hi, {user}. I'm {her}.”*
   - Agent queue item: learn timezone and what matters — slowly, not as an interview.
   - If chat messages already came back from a backup, this seed is skipped.
7. Her and Settings become available. Memory appears when the experimental flag is on.
8. The first time that main UI appears, Android asks for calendar read/write, contacts (`READ_CONTACTS`), and notifications on API 33+, in one `RequestFirstRunPermissions` sequence.
   - The ask is stored as `runtimePermissionsAsked` and `contactsPermissionAsked` so it does not repeat. An install from before contacts joined the ask has only the first flag set, so it gets just the contacts prompt once.
   - If they grant calendar, `calendarEnabled` turns on; if they grant contacts, `contactsEnabled` turns on.
   - Denial leaves the internal calendar only and contacts off; Settings → Calendar access / Contacts access can ask again.

Credentials stay in `SecureSettingsStore` (`her_secure_settings`, EncryptedSharedPreferences). They never go into Room or a backup file.

Optional later, in Settings: Calendar access and Contacts access (each requests permission if needed), web search.

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
- When her reply made, changed or showed a named list, the list sits under it as a checklist (`ui/her/Checklist.kt`), inside the reply's scroll, once the reply is whole.
  - Each row is a real checkbox (`toggleable`, role Checkbox, 48dp tall, a ring when focused) whose text is plain, never markdown.
  - Ticked items dim and strike through. The tick draws in and snaps under reduced motion.
  - A tap saves through `HerRepository.setListItemDone` and sends nothing. The rows follow Room, so it updates in place.
  - A failed save leaves the box as it was and shows the notice card; **Try again** saves it again.
  - Her changes to a list undo from the receipt line.
- The composer is a glass pill that warms when focused.
  - The send button springs in once there is text.
  - On send, the text floats up and fades, and a confirm haptic plays.
  - The composer always enqueues; send is never blocked by an in-flight turn.
- **Offline:** messages are stored as `PENDING`, and a status chip reads `offline · N waiting` beside a `weaving` orb. When the network returns, `processOutbox()` sends the whole batch as **one** turn.
- **Send errors:** a glass card above the composer shakes once with a reject haptic while the orb dims. It is never saved to chat or sent to the model.
  - `AgentOrchestrator.failureOf` sorts the failure into a `TurnFailure`, and `failureNotice` words it in English or Persian, following their last message.
  - **Her Cloud** answers no credit, **Settings** answers a refused key or wrong settings, and **Try again** runs `processOutbox()` again. **Details** shows the redacted raw error.
  - The card clears on the next send or when a later attempt works. A successful **Save & test** sends the waiting message.
- History still lives in Room and still feeds `ContextBuilder`. It is simply not displayed.

Assistant replies are markdown (`**bold**`, lists, headings). Parsing and Compose rendering are local:

- `app/src/main/java/com/her/ui/markdown/MarkdownModel.kt`
- `app/src/main/java/com/her/ui/markdown/ConversationMarkdown.kt`

Share (`ACTION_SEND` text/plain) lands in the same conversation via `HerViewModel.consumeShare()`. A shared image (`image/*`) goes through `consumeSharedImage()`: `ImageReader` sends it once to the chat model to write out its text, and that text becomes the share. The image itself is never stored. The shared text rides in the next user message's `metadataJson.shared`, and `ChatMessage.promptText()` adds it to what the model reads.

## Memory

`SqlNavigatorScreen` reads top to bottom:

- **At a glance:** five tiles counting active, unforgotten long-term memories, understandings, short-term notes, people and important dates (`SqlBrowser.memoryCounts()`).
- **Lately:** the ten newest rows across `long_term_memories`, `short_term_memories`, `user_understandings`, and `important_dates` (`SqlBrowser.recentMemories()`, skipping forgotten and non-active ones); **Show more** extends it to thirty. Each reads Remembered, Noted, Learnt, or Updated, with a relative time. Tap one for the full text in a bottom sheet.
- **How she feels:** her projected feeling (`HerViewModel.feeling`) as a big word beside a still orb. The second feeling in the blend shows as "and a little …". The cause line is the projection's `note` from `feelingCause()`, which picks the recent emotional event that weighs most toward that feeling (its interpretation, else its quoted evidence) or, for concern, the nearest deadline.
- **Who she is:** her six traits (`HerViewModel.persona`) as a web drawn on a `Canvas`. The dashed outline is the seed from her name, the fill is who she is now, and a caption names any trait she is working on because they asked. See [Personality](Personality.md).
- **What she knows:** long-term memories by `category` and understandings by `facet` (`SqlBrowser.knownTopics()`), biggest topic first with a count badge. Tap a topic to open its items, most important first; tap an item for the full text.
- **People she knows:** `people` by last mentioned (`SqlBrowser.people()`), with relationship. Tap one for their notes, preferences and birthday.
- **Advanced:** collapsed each time the tab opens. Opening it reveals a read-only browser over `her.db`:
  - The table list (counted only once Advanced first opens), from `sqlite_master` with row-count badges, shows six tables per page. Skeleton rows appear if loading is slow.
  - Tap a table → `SELECT * FROM "<table>" ORDER BY rowid DESC`, and the list scrolls down to its rows.
  - Free-form query card (must start with `SELECT`, `WITH`, `PRAGMA`, or `EXPLAIN`; stacked statements are rejected). The Run button shows a `searching` orb while a query runs.
  - Results come 50 rows per page with ‹ › controls. `SqlBrowser.query(offset = …)` skips rows on the cursor, so any read-only statement pages without rewriting its SQL.
  - Result grid with alternating rows; the first rows rise in. Tap a cell to open the full value in a bottom sheet.
  - Errors show in a card that shakes once.

Writes (`DELETE`, `UPDATE`, `INSERT`, `DROP`, …) are rejected by `SqlGuard`.

## Settings

Settings is grouped into glass cards that rise in on first open. Each `SectionCard` has a one-line `description` under its title.

- **Her mind:** connection only: the model in use, then the same `HerCloudConnect` buttons as setup. LLM endpoint, key, and model sit under the folded **Endpoint details** row, which opens by itself when a check fails. **Save & test** shows the connecting orb, then a drawn check.
- **What she can use:** calendar, contacts, web search, and embeddings. The custom search endpoint and key appear under web search only while it is on; the embedding model appears under embeddings only while that is on.
- **Quiet hours & reminders:** two time chips that open a 24-hour time picker and save on confirm.
  - **Hourly during quiet hours** (default off).
  - **Allow background work** while the OS is still battery-optimizing the app.
  - **Allow exact alarms** on Android 12+ while exact alarms are not granted.
- **Backup:** **Save backup** (system file picker, `her-backup-YYYY-MM-DD.zip`), **Share** (share sheet), **Import**, which asks "Import this backup?" before it replaces everything and restarts the app, and **Export as text**. The result or error shows under the buttons.
- **Usage today:** API usage numbers that count up.
- **Advanced:** expands with a turning chevron.
  - **Background thinking:** background model and daily background token cap.
  - **Experimental:** **Memory navigator (SQL)** (default on) and **Show what she saved** (default on).
  - **Developer:** developer mode, and manual run hourly / nightly.
  - **Logs:** activity, agent runs, and debug, each folded under its own row.

The footer links to GitHub and credits thinking-orbs.

API credentials are set on each phone; a backup never carries them.

## Other ways in

- **Quick-settings tile** (`entry/HerTileService`): opens `MainActivity` and collapses the shade.
- **Assistant app**: `MainActivity` answers `ACTION_ASSIST`, so Her can be chosen under the phone's default assistant setting. It opens the app; there is no voice interaction service.
- **Share sheet**: `ACTION_SEND` with `text/plain` or `image/*`.
- **Home-screen widgets** (`widget/HerWidget.kt`), plain RemoteViews drawn by `Widgets.refreshAll`. `HerApplication` watches the latest assistant message, the projected feeling (compared by what they show, not by time) and whether `orchestrator.turnState` is idle, and redraws on change, and again on a light/dark switch; no polling. On Android 12+ each widget hands the launcher several layouts by size; older launchers get the middle one. They share the app's look: `widget_bg` (ground, ambient glow, glass edge), the `widget_pill` glass button and Vazirmatn.
  - **Her** (`HerWidget`): her latest message, markdown flattened by `markdownToPlain`. One row high is a single-line bar (`widget_her_row`) with her orb at the start; taller is a card (`widget_her`) with the line centred. While a turn runs (in the app or from the outbox) the card swaps the line for her orb, and the row's orb moves. The orb is the app's own: `WidgetOrb` draws `ThinkingOrb`'s Breathing marks inside `HerPresence`'s halo into bitmaps, a still frame at rest and a 24-frame loop that a `ViewFlipper` (`widget_thinking_orb`) plays while she replies, since RemoteViews can't run the live drawing. Tapping the text opens `MainActivity`; **Write** opens `QuickNoteActivity`, a dialog with one field that queues the note through `AppGraph.queueFromOutside` (the same path as a notification reply) and closes. Before setup it opens the app instead.
  - **How she feels** (`HerFeelingWidget`): the same feeling the Memory tab shows (`WidgetFeeling.of`), as the same orb the Memory tab shows beside it (`WidgetOrb.feeling`: the small Breathing ring, paused, in Her's glow), its word, a second feeling, and her note. Layouts by size: one cell is just the orb (`widget_feeling_small`), a square adds the word and second feeling (`widget_feeling_square`), wide puts the orb beside word, second and note (`widget_feeling`), tall stacks them (`widget_feeling_tall`). The word is one line and auto-sizes instead of wrapping. Tapping opens the app.
