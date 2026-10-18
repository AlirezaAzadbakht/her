# Changelog

Notable changes to Her, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions match the `vMAJOR.MINOR.PATCH` release tags. Signed APKs are on [GitHub Releases](https://github.com/AlirezaAzadbakht/her/releases).

## [Unreleased]

### Added
- The website is now available in Persian at /fa.
- **Feelings with continuity and purpose.** Her can remember emotional experiences, distinguish offering help from execution and reported benefit, carry mixed feelings that settle over time, correct her understanding of your feelings, and quietly reflect on what helped. Her orb follows this evidence-backed state.
- **Deeper feeling model.** Repeated experiences no longer slam into a ceiling; calm gives way to stronger feelings; purpose and a little curiosity or patience change how she answers. Fired reminders and learning progress can count as executed help; an approaching deadline can add concern; saved support lessons come back when a similar situation returns. The orb can mix two feelings, including hopeful, pensive and relieved.

### Changed
- Emotional state is a saturating projection with tone hints in context, rather than a capped running total and unused purpose scores.

### Fixed
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
