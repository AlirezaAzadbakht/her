# Changelog

Notable changes to Her, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions match the `vMAJOR.MINOR.PATCH` release tags. Signed APKs are on [GitHub Releases](https://github.com/AlirezaAzadbakht/her/releases).

## [Unreleased]

### Added
- Apple clients now include system and synced internal calendars, timed/repeating/conditional reminders, conversational person/place reminders, optional automatic reviews with quiet hours, and Android-compatible Google Drive change logs. iOS controls background timing; live Google access requires the owner's Apple OAuth setup and signing. See apple/GOOGLE_SETUP.md.
- No API key? The first screen and Settings link to Her Cloud, where you can buy credit. Then tap Paste settings or Scan QR code to connect with what it gives you.
- Executive delegation extends waiting-on records with owned outcomes, dependencies, reported progress, blockers and decisions needed. Ask “what needs me today?” for an evidence-grounded exception briefing; missing updates remain unknown.
- Moving to a new phone? On the first screen, tap Restore from Google Drive to bring back your conversation and everything she knows.
- Her is now on Cafe Bazaar, linked from the website and README.
- Her can assess progress, her contribution, expectedness and uncertainty, link feedback to ongoing experiences, and keep unresolved questions open as feelings settle.
- Quiet reflections can become tentative support lessons with exceptions; later feedback helps her judge which approaches work for you.
- Internal feelings now have a separate outward tone and next-step decision, so feeling useful does not mean celebrating over your distress.
- The website is now available in Persian at /fa.
- **Feelings with continuity and purpose.** Her can remember emotional experiences, distinguish offering help from execution and reported benefit, carry mixed feelings that settle over time, correct her understanding of your feelings, and quietly reflect on what helped. Her orb follows this evidence-backed state.
- **Deeper feeling model.** Repeated experiences no longer slam into a ceiling; calm gives way to stronger feelings; purpose and a little curiosity or patience change how she answers. Fired reminders and learning progress can count as executed help; an approaching deadline can add concern; saved support lessons come back when a similar situation returns. The orb can mix two feelings, including hopeful, pensive and relieved.

### Changed
- Replies start faster: she sends less with each request.
- When you ask her to forget something, she confirms briefly without repeating the details.
- If two calendar events could match what you asked to move, she asks which one instead of changing either.
- Saying “resume” on a draft she postponed gets the missing part written right away.
- When she saves a birthday or anniversary, she names the day, so you can catch a misheard date.
- Connecting Google Drive and Calendar no longer asks for a client ID; just tap Connect Google.
- Saving reminders and work drafts automatically contributes evidence of Her's work without assuming it benefited you. Marking your own task done no longer gives her credit for doing it.
- Repeated reports linked to the same emotional outcome no longer refresh its age or reward it again; corrections and forgetting also reach derived lessons and their feedback.
- Emotional state is a saturating projection with tone hints in context, rather than a capped running total and unused purpose scores.

### Fixed
- With hourly checks allowed during quiet hours, a nudge held overnight now reaches your phone in the morning instead of sitting silently in chat.
- Telling her how a decision turned out keeps the reasons you first gave and marks the decision reviewed.
- Saying “OK, I'll do that” to one of her suggested actions now counts as accepting today's plan.
- A weekly routine set up the day before (say, “every Saturday” on a Friday) starts tomorrow, not a week later.
- Skipping or postponing one week of a routine no longer fails depending on how she refers to the routine or the new time.
- A briefing on a person no longer fails when she checks the time first, and it leaves out memories that only mention them.
- Times ending in UTC or GMT, such as “Saturday at 10:00 UTC”, are understood.
- Saying yes to deleting a phone-calendar event works: the confirmation id stays visible for the next message.
- An event starting within about an hour and a half gets one short nudge.
- A decision still saves when a reason is not an exact quote; that part is left blank.
- Pausing a routine finds the schedule already saved for it.
- A birthday written as a Jalali date such as 1405-12-12 is stored as the real calendar day.
- Her now remembers your birthday, and other details you mention, even when you share them while feeling down.
- Nightly reviews can list projects without a search term, avoiding an empty-query error that could interrupt emotional reflection.
- Forgetting everything, or deleting an event on the phone calendar, waits for your next message. She cannot confirm those herself.
- If a reply fails after she already saved something, retrying that message does not create it twice.
- Background passes cannot wipe memory, delete calendar events, or change your profile.
- You can stop a reply while she is still thinking.
- An overdue nudge is no longer repeated a few hours later.
- Reminder and calendar times such as “today at noon” and “tomorrow noon” are now understood directly.
- Decision-saving guidance preserves complete source quotations instead of joining separate reasons into unsupported wording.
- Emotional reviews now retrieve saved experiences when the model leaves the optional record ID blank, with clearer guidance for source quotes, corrections and reflection fields.

## [0.2.0] - 2026-10-17

### Added
- **Ongoing support.** Keep life threads, track replies owed by others, prepare for meetings, and resume working drafts, with optional personalized check-ins.
- **Memory you can question and correct.** Ask what she remembers and why; update facts while keeping relevant history, or forget a specific record.
- **Plans that fit your day.** Choose small actions using time, stated energy, prerequisites and known meetings, without moving your commitments.
- **Flexible recurring responsibilities.** Skip or postpone one occurrence, pause a routine, and resume without cancelling the whole responsibility.
- **Decisions and reflection.** Keep the reasons you actually gave for important choices and optionally revisit them later.
- **Briefings for the moment.** Catch up after time away, prepare to leave, review outstanding matters with someone, or wrap up the week.
- **Learning in chat.** Ask her to teach you something and she proposes a short roadmap, teaches one small lesson at a time, checks your answers, saves progress, and schedules spaced reviews. Optional study reminders follow quiet hours. See [Learning](wiki/Learning.md).

### Changed
- Her English replies are set in Comfortaa instead of SuperChiby.

## [0.1.7] - 2026-10-07

### Added
- **Feeling index.** Her keeps a current feeling that sets the tone of her voice, and her orb and the background lights take its colors.
- The thinking orb changes its look every few messages, and its halo flows.

### Changed
- The composer grows as you type and follows the direction of the text, so the caret stays in view when you type right to left.
- Enter sends and Shift+Enter starts a new line.
- CI now runs lint and builds the R8-obfuscated release APK on every push.

### Fixed
- The send button works after the composer has grown to fit long text.
- Memories marked outdated or archived no longer come back in recall as if they were current.
- "Forget everything" also removes outdated and archived memories, and asks for confirmation first. Forgetting a memory that does not exist reports an error instead of claiming success.

## [0.1.6] - 2026-09-15

### Fixed
- CI no longer asks `sdkmanager` for the removed `tools` package, so the signed APK for the 0.1.5 changes could finally publish.

## [0.1.5] - 2026-09-15

The release APK did not publish for this tag because of a CI failure. The same changes shipped in 0.1.6.

### Added
- **Reminders and alarms.** Reminders ring on time even without the network or the model, and alarms go to your clock app.
- She understands natural times: plain weekdays, parts of the day ("Thursday morning"), clock ranges, a trailing time zone, deadline words, and Persian relative days.
- **Warm-glow redesign.** A living presence orb, replies that ink in word by word, an animated splash, a dotted-ring app icon, a floating nav, haptics, and restyled onboarding, settings, and memory screens.
- The website was redesigned to match the app, with live orbs and real screens.

### Changed
- She declines invitations as herself, and answers a fact you share in one reply without reading it back to you.

## [0.1.4] - 2026-09-14

### Added
- **Write receipts with undo.** A short note under her reply shows what she saved, and you can undo it.
- **Reply from a notification.** Notifications have a Reply action, and Later keeps the notification text.
- **Nightly digest.** Decisions and context outlive the recent chat.
- **Search by meaning (optional).** When embeddings are enabled, she finds memories by meaning.
- She keeps an evolving picture of how you like to communicate, and revises it each night.
- She can read today's phone and Google Calendar meetings, and any date or hour range.
- Current public facts are looked up through your configured model.
- Settings has an About section with a link to the source on GitHub.

### Changed
- Drive sync covers every record, not just groceries and chat.
- Replies stream like ChatGPT: they grow from the start with a caret and don't flash away. Her replies are set in SuperChiby.
- Quiet hours and "today" follow the time zone in your profile instead of the device's.
- Live-model scenarios no longer run in CI. The scenario pool grew to 92, and fixes for the problems it found are included in this release.

### Fixed
- Memory search works for whole sentences and for Persian.

## [0.1.3] - 2026-09-13

### Added
- Jalali dates and better parsing of times.
- Calendar tools write to the device calendar, and the app asks for calendar permission at runtime.
- A scenario pool that tests behavior against a live model.

### Changed
- Smaller APKs, from R8 minification and resource shrinking.

## [0.1.2] - 2026-09-12

### Added
- **Single-utterance home.** The screen shows only her latest message, streamed in place.
- **Offline queue.** Notes written offline wait and are handled together when the network returns.
- A Memory SQL navigator for inspecting what she has stored.
- First run asks for your name and hers before the model settings.

### Changed
- Replies that start with Persian or Arabic are laid out right to left.
- The interface uses Vazirmatn, so Latin and Persian text read in one voice.
- A small, quiet pause replaces the full-screen animation while she thinks.

### Fixed
- Setup no longer blocks the UI thread, so Continue leaves the form after a successful connection check.

## [0.1.1] - 2026-09-12

### Fixed
- CI unit tests pass on GitHub Actions. This is the first release with a signed `her-0.1.1.apk` attached.

## [0.1.0] - 2026-09-12

The first product cut. The release APK job did not finish because CI tests failed.

### Added
- One conversation for the life of the app, with structured memory and autonomous tools.
- Messages render as markdown, and Enter sends.
- A Memory page for lasting facts.
- Signed APKs publish from version tags.

[Unreleased]: https://github.com/AlirezaAzadbakht/her/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.7...v0.2.0
[0.1.7]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/AlirezaAzadbakht/her/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.0
