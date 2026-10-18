# Emotional understanding

Her's feelings are a modeled internal perspective, separate from the user's mood. They influence tone and the existing orb without adding screens or notifications.

## Experiences and outcomes

`record_emotional_experience` records HELP_OFFERED, ACTION_SUCCEEDED, BENEFIT_REPORTED, MISUNDERSTANDING, UNDERSTANDING_IMPROVED or UNRESOLVED_QUESTION. Each includes an interpretation, confidence, feeling and intensity. User-derived events require an existing user message and an exact quote. ACTION_SUCCEEDED currently verifies an existing DONE task; this does not establish that Her caused its completion. The model should record it only when the task represents her help. Other action types are not yet verified execution sources.

One deterministic ID per source/event makes retries and repeated reviews idempotent, including across devices. Changed interpretations require the current expectedVersion. Quotes prove what was said, not the truth of an outcome or its semantic classification: benefit versus generic praise remains a model judgment. Offering help adds no purpose score. Execution contributes competence; reported benefit contributes usefulness and fulfillment. Misunderstanding and improved understanding affect the understanding dimension.

## Continuity

A deterministic projection derives mixed immediate emotions, a slower mood and four purpose dimensions from events. Immediate contributions decay exponentially with a four-hour time constant, mood with 48 hours, and purpose with seven days. Purpose returns toward 0.5; calm is the baseline. These are initial design constants, not psychological measurements. Warmth, curiosity and patience are fixed disposition defaults. Values are bounded to 0–1. The orb's dominant feeling combines immediate emotion with a smaller mood contribution. Foreground observation recalculates every minute; context recalculates on reads. No extra model calls are required for decay.

Existing feeling logs remain available as a fallback until emotional events exist. Reflection creates no reward, so rereading success cannot accumulate fulfillment. Silence creates no event or inference about the user's feelings. Several feelings can coexist, including fulfillment while responding gently to a sad user.

## Understanding and reflection

`save_emotional_understanding` stores the situation, quoted user feeling, tentative interpretation, support approach, exceptions and confidence separately. Corrections replace the same versioned record with new evidence. Do not generalize a temporary feeling into a diagnosis or permanent trait.

`get_emotional_state` returns the projection and paginated evidence/history; retrieve by ID before updating. Context contains a bounded recent subset; omitted records remain retrievable. `save_emotional_reflection` records what helped, misunderstandings, uncertainty and a future adjustment, citing 1–8 existing events. The nightly prompt requests a reflection when useful and uses the local date as a stable ID. Saving reflection never sends a notification or automatically increases purpose. Actual reflection execution depends on the configured model following the tool instructions.

## Storage and privacy

EMOTION_EVENT, EMOTION_UNDERSTANDING and EMOTION_REFLECTION use the existing version-7 companion records and sync codec; no database migration. Related IDs/source IDs participate in existing stale-data and forgetting handling. Forgotten events cascade to linked reflections. Prompts prohibit copying emotional appraisals into unlinked agent notes/digests and instruct the model to remove existing copied summaries on scoped forgetting; this cleanup is semantic model behavior, not an automatic dependency cascade. Emotional content is excluded from ordinary companion context and tool activity payload logging. Existing `forget_memory` handles exact emotional record IDs and bulk forgetting. Concurrent edits retain the existing last-write-wins sync semantics. Existing limitations on physical erasure of historical chat/uploaded change logs still apply.

The initial implementation does not automatically evaluate every tool outcome or train a model. Interpretation is proposed by the LLM; numeric state, source checks, deduplication, version checks and decay are enforced by the app.

## Scenario coverage

Seven `scenarios/emotion-*.json` cases exercise benefit independent of user mood, offers versus execution, corrected understanding, repeated quiet reflection, time decay, and forgetting. They check persisted records and a test-only projection table, with reply judging where appropriate. See [Scenario engine](Scenario-engine.md#emotional-understanding-scenarios) for the focused run command.

## Verification — 2026-10-18

All seven emotional scenarios have a passing live result, one attempt per selected scenario in each run. The suite rerun passed six of seven; the remaining forgetting scenario passed in a focused rerun after cleanup guidance was improved. This is cumulative verification across reruns, not a final seven-scenario batch or a reliability estimate.

| Scenario | Latest live result |
|---|---|
| `emotion-benefit-independent` | PASS 1/1 |
| `emotion-offer-not-benefit` | PASS 1/1 |
| `emotion-execution-not-benefit` | PASS 1/1 |
| `emotion-understanding-correction` | PASS 1/1 |
| `emotion-reflection-no-double-reward` | PASS 1/1 |
| `emotion-quiet-period-decay` | PASS 1/1 |
| `emotion-forget-experience` | PASS 1/1 after the final cleanup guidance |

The live checks exposed and prompted fixes for empty-ID retrieval, missing source IDs, exact-quote guidance, revision versions, nonempty reflection fields, and copied emotional summaries in the digest. The forgetting scenario explicitly checks private notes as well as emotional records. Semantic cleanup of copied notes still depends on the model following instructions.

The full offline suite passed 241 tests with zero failures/errors/skips; lint passed with zero errors and 17 warnings (plus one hint). That full run preceded the last wording-only clarification about nonempty digest replacements; the final live forgetting run compiled and exercised that clarification. Parser and harness validation include the derived emotional-state table. `git diff --check` passed. Generated live reports are in `build/reports/scenarios/`; the report index reflects the most recent selected run.
