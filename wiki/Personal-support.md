# Personal support

These capabilities extend the continuous text conversation and existing companion records. They add no screens, permissions, dependencies, or background schedules.

## Understandable memory

`inspect_understanding` retrieves a topic or exact memory ID in bounded pages, with source type, source message/date when present, update time, and current/historical status. It includes durable facts, temporary memories and user understanding. `get_life_context` retrieves situation and decision evidence.

`correct_memory` requires a specific existing ID, current version, stable replacement ID and quoted user evidence. It creates the replacement as current and preserves the previous fact as historical; temporary facts get a historical copy. Related long-term summaries become historical; linked companion summaries become stale and cannot generate check-ins until refreshed. Content-free SUPERSESSION markers prevent a stale synced original from becoming current again, including after its replacement is forgotten. Late-arriving derived summaries remain historical. Semantic contradictions in unrelated profile fields or records still require the agent to inspect and correct those records; the application cannot infer every semantic dependency.

Scoped forgetting uses existing `forget_memory`. Broad forgetting retains its confirmation gate. Content-free FORGOTTEN markers survive sync, including deletion before the original row arrives. Known derived memory and companion dependencies cascade, tombstoned content is blanked, and embedding cache entries are removed. An in-flight embedding request rechecks record visibility before caching or returning results. Source-linked understanding follows source deletion. FTS follows the underlying Room content updates. Expired short-term and historical understanding records are included in bulk forgetting.

This is logical deletion, not guaranteed complete erasure. Historical chat, previously uploaded Drive change logs, and legacy unlinked copies are not physically erased. The agent must not recreate forgotten information from old chat or copy summaries into unlinked agent notes. Stored quotes prove what was said, not whether a fact is objectively true.

## Capacity-aware planning

`save_action_capacity` attaches conservative duration estimates, stated energy needs, priority and prerequisite IDs to an existing task or commitment. Uncertainty should be included in the upper duration estimate. Unknown estimates are returned as unknown, rather than assumed to fit.

After refreshing the existing calendar integration, `suggest_daily_plan` returns up to three actions, fitting the uninterrupted window before the next known event. It checks prerequisites against completed records and filters known energy demands. It orders by deadline and then priority. Cyclic or unresolved prerequisites remain blocked. Missing calendar end times reserve an hour. Calendar coverage is limited to the local mirror; the result says so.

`accept_daily_plan` records explicit acceptance with evidence and rechecks capacity. Stable IDs make identical retries no-ops. A plan expires with its time window and creates no daily task backlog. Replanning is read-only: no deadlines, promises or calendar events move. Use the existing `save_work_session` with a selected action ID to begin/resume work. Suggestions, acceptance, drafts, task completion and external actions remain separate.

## Recurring responsibilities

`save_responsibility_schedule` extends an existing routine, responsibility or reminder. It stores fixed/optional classification, a local date-time anchor, daily/weekly/monthly recurrence with interval 1–52, timezone policy, and pause state. It can bind one existing explicitly requested clock reminder; it never creates one implicitly. There is one schedule per responsibility and one owner per bound reminder. Other recurrence patterns require conversational adjustment to a supported schedule.

`get_life_context` exposes the next scheduled timestamp. `manage_occurrence` requires the original scheduled timestamp and explicit evidence to skip, postpone or complete one occurrence. Skipping leaves the next occurrence intact. Forgetting a schedule cancels its bound reminder. Postponement must be in the future and before the following occurrence. Late completion does not consume the next reminder. Repeated identical operations are no-ops. Pausing indefinitely or until a date preserves the routine; resumption uses the same schedule. Changing schedule fields affects future calculations.

Occurrence indices preserve a skip across profile timezone changes. Occurrences are calculated from the original local anchor, avoiding permanent drift after month-end clamping or DST gaps. Java time shifts nonexistent local times forward and chooses the earlier offset for ambiguous times. PROFILE follows profile timezone changes; FIXED stays in the saved zone. Existing alarm rearming/background passes apply updated reminder times. Existing reminders without schedules retain their recurrence behavior.

Delivery uses the existing offline alarm/worker path and device ownership. It rechecks skipped/completed/paused/postponed state inside a transaction—including when a synced occurrence arrives before its reminder update—and reserves a stable delivery record before notifying. Reservation prevents duplicate local deliveries but can miss a notification after a crash; it is not distributed exactly-once delivery. Explicit clock reminders retain their existing notification semantics. Schedules cannot become companion check-in topics, preventing a second feature from nudging the same bound responsibility. No streak tracking or missed-occurrence escalation is added.

## Decision memory

`save_decision` stores EXPLORING, DECIDED or REVIEWED records linked to existing situations/projects/goals. Final decisions require explicit evidence. Reasons, alternatives, constraints, assumptions and reported outcomes must quote actual user words; missing fields remain unknown. Partial updates preserve field-level evidence, so reporting a changed workplace does not replace the original apartment rationale.

An optional review date uses the same topic opt-in, sensitivity consent, quiet hours, pause, dismissal, frequency and daily-limit gates as other companion check-ins. Condition-only reviews are conversational; no new monitor is installed. Outcomes must not become unsupported personality judgments.

## Focused briefings

`get_situation_briefing` combines relevant tasks, commitments, local events, waiting-on items, threads and decisions within a bounded window. It prioritizes deadlines and recent changes, returns up to eight items, identifies omissions, and marks stale/missing coverage. Expand with existing record tools. It creates no saved briefing copy or notification schedule.

Person briefings require an exact person ID. They use explicitly linked commitments/threads/waiting-on/decisions, exclude sensitive companion records, and do not search arbitrary personal memories for name matches. Unlinked calendar events are intentionally omitted from person briefings. Briefings describe stored evidence, not monitoring of inaccessible sources.

## Persistence and verification

DECISION, CAPACITY, PLAN, SCHEDULE, OCCURRENCE, DELIVERY, SUPERSESSION and FORGOTTEN reuse the existing version-7 `companion_records` schema and JSON codec. No schema migration is needed; the registered migration chain and existing migration test remain unchanged. Version checks, atomic document merges, deletion-wins companion tombstones and offline sync queues are reused. Memory corrections use existing history/status/provenance columns. New DAO queries only change retrieval/cache removal, not table definitions.

New decision/capacity/plan creation receipts support existing creation undo. Schedule/occurrence edits and corrections produce non-undoable edit receipts; they do not misleadingly offer restoration of a prior version. Sync is last-write-wins for concurrent edits of the same document, not a semantic merge; two offline devices can still make conflicting choices.

`PersonalSupportTest` exercises correction/history, derived forgetting and cache removal, late sync/no echo, capacity/dependencies/calendar, explicit plan acceptance, skip/postpone/pause/late completion, DST/month-end/timezone behavior, rationale validation, sensitive/stale briefings and receipt undo. Five `scenarios/support-*.json` scenarios exercise live-model routing separately from deterministic tests. Scenario parsing alone does not prove live-model adherence or Android notification delivery.

Verification on 2026-10-17: `gradle :app:testDebugUnitTest :app:lintDebug --offline` completed successfully. The unit suite reports **233 tests, zero failures/errors/skips**, including 16 `PersonalSupportTest` cases and the existing companion, migration, reminder and sync tests. Android lint reports **zero errors and 17 warnings**. `git diff --check` is clean. The five new live-model scenarios passed the ordinary parser validation but were **not run against a live provider**. Real-device notification delivery was not tested.
