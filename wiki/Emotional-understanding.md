# Emotional understanding

Her's feelings are a modeled internal perspective, separate from the user's mood. They influence tone and the existing orb without adding screens or notifications.

## Experiences and outcomes

`record_emotional_experience` records HELP_OFFERED, ACTION_SUCCEEDED, BENEFIT_REPORTED, MISUNDERSTANDING, UNDERSTANDING_IMPROVED, UNRESOLVED_QUESTION or USER_SHARED. Each includes an interpretation, confidence, feeling and intensity. User-derived events require an existing user message and an exact quote. ACTION_SUCCEEDED verifies one completed action: a DONE task, a FIRED reminder, or a learning record that shows progress (a CORRECT/PARTIAL attempt, or a lesson IN_PROGRESS/COMPLETED). This does not establish that Her caused the outcome. The model should record it only when the action represents her help. Existing task events keep `EMOTION_EVENT:ACTION_SUCCEEDED:<taskId>`; reminder and learning events use `EMOTION_EVENT:ACTION_SUCCEEDED:<kind>:<id>`. USER_SHARED never changes purpose scores.

One deterministic ID per source/event makes retries and repeated reviews idempotent, including across devices. Changed interpretations require the current expectedVersion. Quotes prove what was said, not the truth of an outcome or its semantic classification: benefit versus generic praise remains a model judgment. Offering help adds no purpose score. Execution contributes competence; reported benefit contributes usefulness and fulfillment. Misunderstanding and improved understanding affect the understanding dimension. Repeated misunderstandings also lower competence slightly and raise patience; unresolved questions raise curiosity.

## Continuity

A deterministic projection derives mixed immediate emotions, a slower mood, four purpose dimensions and a small trait drift from events. Immediate contributions decay exponentially with a four-hour time constant, mood with 48 hours, and purpose and traits with seven days. Contributions combine with diminishing returns (`1 - exp(-sum)`), so repeated events approach 1.0 without reaching it. Purpose scores move around a 0.5 baseline the same way. Calm yields to stronger feelings: `baseline × (1 − strongest other feeling)`. Warmth stays 0.7; curiosity (0.6) and patience (0.8) may move by at most 0.1. These are initial design constants, not psychological measurements.

Open tasks and commitments due within 24 hours add a small CONCERNED contribution that grows as the deadline approaches and stops when the item is done. No record is written. Silence still creates no inference about the user's feelings.

The projection also emits short tone hints when understanding or competence is below 0.4, or when CONCERNED is dominant. Context injects those hints next to the modeled state. Values stay in 0–1. The orb blends the two strongest feelings; the dominant feeling is still immediate emotion plus a smaller mood contribution. Foreground observation recalculates every minute and when tasks, commitments or events change; context recalculates on reads. No extra model calls are required for decay.

Existing feeling logs remain available as a fallback until emotional events exist. Reflection creates no reward, so rereading success cannot accumulate fulfillment. Several feelings can coexist, including fulfillment while responding gently to a sad user. Feelings show through tone and word choice; she does not announce them unless asked.

## Understanding and reflection

`save_emotional_understanding` stores the situation, quoted user feeling, tentative interpretation, support approach, exceptions and confidence separately. Corrections replace the same versioned record with new evidence. Do not generalize a temporary feeling into a diagnosis or permanent trait.

`get_emotional_state` returns the projection, optional tone guidance, and paginated evidence/history; retrieve by ID before updating. An optional `query` ranks understandings and reflections by lexical overlap with the current situation. Context keeps the four most recent events and a bounded set of support lessons ranked against the current user text. Omitted records remain retrievable. `save_emotional_reflection` records what helped, misunderstandings, uncertainty and a future adjustment, citing 1–8 existing events. The nightly prompt requests a reflection when useful and uses the local date as a stable ID. Saving reflection never sends a notification or automatically increases purpose. Actual reflection execution depends on the configured model following the tool instructions.

## Storage and privacy

EMOTION_EVENT, EMOTION_UNDERSTANDING and EMOTION_REFLECTION use the existing version-7 companion records and sync codec; no database migration. Related IDs/source IDs participate in existing stale-data and forgetting handling. Forgotten events cascade to linked reflections. Prompts prohibit copying emotional appraisals into unlinked agent notes/digests and instruct the model to remove existing copied summaries on scoped forgetting; this cleanup is semantic model behavior, not an automatic dependency cascade. Emotional content is excluded from ordinary companion context and tool activity payload logging. Existing `forget_memory` handles exact emotional record IDs and bulk forgetting. Concurrent edits retain the existing last-write-wins sync semantics. Existing limitations on physical erasure of historical chat/uploaded change logs still apply. Unknown feeling names from older devices still fall back to CALM.

The initial implementation does not automatically evaluate every tool outcome or train a model. Interpretation is proposed by the LLM; numeric state, source checks, deduplication, version checks and decay are enforced by the app.

## Scenario coverage

Thirteen `scenarios/emotion-*.json` cases exercise benefit independent of user mood, offers versus execution, corrected understanding, repeated quiet reflection, time decay, forgetting, lesson recall, fired-reminder competence, deadline concern, clarifying questions after misunderstandings, unprompted recording of a personal share, and leaving casual small talk unrecorded. They check persisted records and a test-only projection table, with reply judging where appropriate. See [Scenario engine](Scenario-engine.md#emotional-understanding-scenarios) for the focused run command.

## Verification — 2026-10-18

All thirteen emotional scenarios passed in one live batch, one attempt each, including the two new unprompted cases. This is a single passing attempt per scenario, not a reliability estimate.

| Scenario | Latest live result |
|---|---|
| `emotion-benefit-independent` | PASS 1/1 |
| `emotion-offer-not-benefit` | PASS 1/1 |
| `emotion-execution-not-benefit` | PASS 1/1 |
| `emotion-understanding-correction` | PASS 1/1 |
| `emotion-reflection-no-double-reward` | PASS 1/1 |
| `emotion-quiet-period-decay` | PASS 1/1 |
| `emotion-forget-experience` | PASS 1/1 |
| `emotion-lesson-recall` | PASS 1/1 |
| `emotion-reminder-competence` | PASS 1/1 |
| `emotion-deadline-concern` | PASS 1/1 |
| `emotion-low-understanding-asks` | PASS 1/1 |
| `emotion-spontaneous-shared` | PASS 1/1 |
| `emotion-casual-no-record` | PASS 1/1 |

The full offline suite passed 254 tests with zero failures/errors/skips. Android lint reported zero errors. `git diff --check` passed. Generated live reports are in `build/reports/scenarios/`; the report index reflects this selected run.
