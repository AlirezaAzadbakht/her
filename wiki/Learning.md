# Learning in Her's conversation

Ask “Teach me Python,” “Help me learn English,” or “Give me a roadmap for physics.” Her clarifies missing goals, experience, language and time, proposes a short roadmap, then saves it after acceptance. Everything happens in the existing conversation; there is no learning tab.

Ask “continue my lesson,” “show my roadmap,” “quiz me,” “explain differently,” “make it easier,” or “pause this course.” Multiple subjects can coexist. Her asks which one when continuation is ambiguous. A quick question does not automatically create a course.

Lessons combine short explanations, examples and one exercise at a time. Exercises and teaching content are saved before use. Hints repeat the question because the interface shows only the latest reply. Unrelated conversation leaves saved learning intact. Reading, hints and skipped material are not counted as successful answers.

## Progress and review

Coverage (planned/in progress/completed/skipped) is independent of evidence from practice. Exact exercises compare normalized answers to an explicit answer set. Open answers use the configured model and a saved rubric; uncertain evaluations do not advance review or mastery. These evaluations are model judgments, not verified certification. Code is not executed and spoken pronunciation is not assessed.

Attempts are separate records, attributed to real user message IDs. A retry for the same exercise and message cannot count twice. Review intervals after consecutive correct answers are 1, 3, 7, 14, then 30 days. Incorrect/partial answers reset the interval to one day; uncertain results leave it unchanged. Three consecutive correct attempts are labelled practiced, not comprehensive mastery.

## Optional study reminders

Agree a local study time in chat. Learning nudges use the existing background passes, so they are approximate rather than exact alarms. They require an active path and explicit opt-in, respect quiet hours and existing notification limits, and attempt at most one nudge per path per local day. Pause/archive or disable reminders to stop them. Review dates alone never authorize a notification. Devices that have not yet synced can independently claim the same day's nudge; sync is eventual.

## Implementation

- Room version 6 adds `learning_records` through migration 5→6, preserving existing data.
- PATH, LESSON, EXERCISE, ATTEMPT and NUDGE records have independent IDs and sync metadata. Content is versioned JSON. Exercises are immutable; lessons retain attempts when edited.
- `LearningTools.kt` validates references, statuses, answer attribution and optimistic update versions. Storage and its sync enqueue are transactional.
- `LearningContext.kt` supplies bounded path summaries, roadmap coverage, latest exercises and review counts; `get_learning` retrieves full details and recent user message IDs.
- `LearningProgress.kt` calculates review dates deterministically from attempts. Independent attempt IDs preserve concurrent practice; edits to the same record use timestamp/device ordering consistently across sync.
- `send_learning_reminder` claims a daily nudge. The orchestrator applies notification policy and saves its text only when delivering it.
- Offline answers use the existing outbox. Saved learning stays on-device; new teaching/grading requires the configured model connection.

Run `testDebugUnitTest` for deterministic storage, migration, tools, sync and delivery checks. Live-model scenarios `learning-chat-roadmap` and `learning-resume` exercise conversational behavior separately with the configured provider. The `capability-learning-*.json` cases additionally cover correct and incorrect answers, hints without recorded attempts, ambiguous course selection, and pausing study nudges. They assert persisted learning records as well as replies; see [Scenario engine](Scenario-engine.md#additional-capability-scenarios).
