# Ongoing support

Her keeps the single continuous conversation and latest-message interface. These features add no dashboard, separate inbox, or navigation requirement.

## Conversation behavior

- **Life threads:** meaningful evolving situations such as a move or job search, with a concise current summary, decisions, questions and next steps. Developments are separate records with quoted source-message evidence. Field-level attribution preserves earlier evidence and uncertainty across partial edits. Explicit statements and tentative interpretations are distinguished. Search old situations, correct summaries/developments, close, pause or reopen through conversation. Casual mentions need not become records.
- **Waiting on others:** extends an existing open loop with a person, expected outcome, deadline and earliest eligible follow-up. The user's own promises remain commitments. Resolution/cancellation updates the linked loop; drafting a follow-up does not send it or complete the user's work.
- **Check-ins:** preferences are inspectable and editable in conversation. Topics must be selected; social reconnection additionally requires explicit opt-in. Preferences include local time, interval (24–720 hours), daily maximum (1–3), tone, sensitive-topic consent, pause-until, pause and disable. Default without preferences is silence. A dismissal stops the topic until an explicit resumption. Android Later snoozes it for 24 hours.
- **Meeting preparation:** refresh the existing calendar integration, retrieve relevant people, memories, threads and promises, and save a short preparation. A changed start/end invalidates eligibility until preparation is refreshed. Cancelled/deleted events cannot notify. An optional outcome invitation asks whether the event happened rather than assuming attendance or promises.
- **Get-it-done sessions:** link to an existing task/commitment, adapt to available minutes and stated energy, and save partial progress, draft and next step. Resume without starting over. DRAFT is not SENT, and session completion never automatically completes the linked obligation. These tools perform no external messaging actions.

## Implementation

`data/db/Companion.kt` follows the existing versioned learning-record pattern, with its own `companion_records` table. Kinds are THREAD, DEVELOPMENT, WAITING, PREFERENCES, PREPARATION, SESSION and NOTICE. `parentId` ties developments to threads and extensions to existing open loops, events or obligations. `relatedIds` links existing people, projects, goals, memories and commitments. No duplicate task, calendar or scheduler system is introduced.

Room version 7 adds the table through `MIGRATION_6_7`; the existing migration chain remains registered. Content is a bounded JSON document (3,000 characters) with metadata outside it. New conversational tools validate types, enums, ranges, references and actual user-message quotations. An edit requires the current version. Stable IDs and identical-content retries avoid repeated writes; duplicate titles/parents are checked on creation where applicable. Developments are separate so concurrent developments do not overwrite one aggregate timeline.

`get_life_context` pages full records below the orchestrator output ceiling and exposes recent user-message IDs. Context construction uses lexical relevance (including Unicode words), at most five abbreviated matches, the preference record, and at most three currently eligible offers. Old/closed records remain searchable regardless of age. This is lexical retrieval, not embedding search; the model can explicitly retrieve full context before answering or editing.

Companion writes use the existing offline outbox, repository transaction and Drive operation queue. Sync codecs round-trip all metadata. Content merges atomically by timestamp/device tie-break, matching the learning-record approach. Companion tombstones win against stale updates; late descendants of a forgotten record are blanked/tombstoned. A new explicit preference request after forgetting can create a fresh preference generation. Concurrent edits of the same record are last-write-wins, not a text merge.

Receipts distinguish newly created records from edits; creation undo uses the same forgetting cascade. `forget_memory` accepts companion IDs; bulk forget includes companion records. Forgetting linked memories removes dependent companion records and descendants. This follows the existing logical-delete model: historical chat and previously uploaded Drive change logs are not physically erased. The correct_memory tool now preserves historical facts and invalidates linked summaries in application code. Corrections should refresh affected summaries and mark mistaken developments SUPERSEDED; the model remains responsible for identifying semantic contradictions in unrelated records. Do not copy companion summaries into unlinked agent notes/digests.

## Proactive delivery

`companion/Companion.kt` is the application eligibility gate. It checks preferences, topic/social opt-in, sensitivity, active status, dismissals/snoozes, quiet hours on the profile clock, interaction within the last two hours, existing notification gap, daily maximum and per-topic frequency. Normal check-ins use a two-hour window beginning at the preferred local time. Preparation uses the two hours before the event; outcome invitations use the 24 hours after its scheduled end. Event phases still share topic frequency and daily limits, so silence may be appropriate after a preparation was already offered.

`send_companion_checkin` reserves a stable NOTICE in a transaction. Meeting keys include the event start and phase; other keys include topic, local date and phase. Only the record's originating device offers the nudge. The orchestrator rechecks eligibility immediately before delivery, persists the delivered text as the latest assistant message, and uses the notice key for the existing notification policy. A suppressed tool call cannot fall back to final prose in that autonomous run. Swiping away the notification persists a dismissal; Later persists a snooze instead of creating an unrestricted agent-queue item.

These offers run on existing background passes, not exact alarms. Reservation favors avoiding duplicates: if a run crashes after reserving, or Android denies notification permission, an offer can be missed. There is no distributed exactly-once guarantee during concurrent offline device edits. The model must route these semantic categories through the dedicated tool; arbitrary prose from older background paths is not semantically classified by application code. Existing reminder alarms and calendar write permissions remain unchanged.

New tool payloads are omitted from activity/debug logs; prompt/response logs contain sizes rather than full private context, and retrieval logs contain IDs.

## Verification

`CompanionTest` covers persistence/reopen, migration from version 6, retrieval after a long gap, correction/closure/reopening, source validation, stale edits/retries, waiting resolution, opt-in/quiet-hours/pause/recent-chat/dismissal, sensitive topics, device ownership, meeting rescheduling/cancellation, resumable drafts, bounded pagination, sync round-trips/no echo, tombstone ordering and late children, receipt undo, and orchestrator delivery/suppression.

Eight `scenarios/companion-*.json` files exercise natural-language routing against a live model. They are parsed by the ordinary suite; running them with `gradle :app:scenarioTest -Pscenario.filter=companion` is a separate network/API verification. Passing deterministic tests does not establish live-model adherence or real-device notification delivery.

Implementation verification on 2026-10-17: the offline unit suite reports 217 tests, zero failures/errors/skips, including 15 companion tests. The eight live-model scenarios were not run: automatic approval review blocked the external LLM invocation pending explicit authorization to transmit scenario prompts/context to the configured provider.

See [Personal support](Personal-support.md) for the extensions to memory, planning, recurring responsibilities, decisions and situation briefings, including sync and notification limitations.
