# Emotional understanding

Her's feelings are a modeled internal perspective, separate from the user's mood. They influence tone and the existing orb without adding screens or notifications.

## Fast feeling, slow reflection

After every successful chat reply, `FastAppraiser` (`feelings/FastAppraisal.kt`) makes one small non-streaming call to the configured model. It does not resend the full system prompt. The call sees six earlier messages, the user's latest message(s), the reply and the open experiences, and offers one tool, `appraise`. The result is recorded for the last user message of the batch:

- `EMOTION_EXPRESSION:<messageId>` with the user context, unless it is NEUTRAL.
- `EMOTION_EVENT:MISUNDERSTANDING:<messageId>` for a correction, or `EMOTION_EVENT:USER_SHARED:<messageId>` for a meaningful personal development. Both carry `origin: FAST` and `contribution: 0`.

The IDs are the same ones `record_emotional_experience` produces, so a later review updates the fast event (with its version and original `occurredAt`) instead of adding a second one. The fast path can lower understanding through a correction, but it never credits Her: usefulness, fulfillment and competence still need quoted evidence. Anything malformed is dropped rather than rejected: an unknown context becomes NEUTRAL, numbers are clamped, and an experience ID that is not open or a quote that is not in the message is left out. A missing or failed call writes nothing and logs "Appraisal skipped" without content. An existing row, including a forgetting tombstone, is never overwritten. A turn in which anything was forgotten (a `FORGOTTEN` marker or a companion deletion at or after the batch's first message) is not appraised at all, so no feeling or quote about the forget request is kept. Small talk and plain requests write nothing. Usage is counted as a chat request.

Chat files nothing itself. `record_emotional_experience`, `save_emotional_understanding`, `save_emotional_reflection`, `save_emotional_thread`, `save_support_lesson`, `record_support_application` and the legacy `set_feeling` are offered only to the nightly pass (`REFLECTION_ONLY_TOOLS` in `ToolRegistry`). Chat keeps the read-only `get_emotional_state` and `forget_memory`. The tools still execute if called, so scenario seeds work. The chat instructions (`EMOTIONAL_INSTRUCTIONS`) now only cover expression, prohibitions, read-only recall, tentative lessons and forgetting. The filing procedure lives in `EMOTIONAL_REFLECTION`, appended to the nightly prompt. It asks the nightly pass to:

1. Group today's FAST events into experiences and link each event to its experience with its current version. A newly created thread adopts the unlinked FAST events of its own source message and lists them as `linkedEvents`, because the review often saved the thread but skipped the link, which left no held concern.
2. Record credit only with quoted evidence. A reported benefit or a completed action is its own `BENEFIT_REPORTED` or `ACTION_SUCCEEDED` event next to the fast `USER_SHARED` one for the same message; the fast event never stands in for either.
3. Record lesson applications and feedback. In the nightly run, `get_emotional_state.applicableLessons` is matched against the day's user messages as well as the query, so a lesson tried today shows up.
4. Reflect and save lessons.

In the nightly run, `get_emotional_state.recentUserMessages` covers the last 24 hours (up to 20 messages) instead of the latest three, so the review can quote the day. A version error names the current version. `record_emotional_experience` stores the experience's full ID even when it was matched from a shorter one. This moved about 4,300 characters out of the chat system prompt (29,941 → 26,100) and 8 tools out of the chat tool list (96 → 88). The `save_emotional_expression` tool is gone; the app writes `EMOTION_EXPRESSION` records itself.

A fast event the appraisal could not tie to an open experience stands alone until the nightly pass links it: they give immediate feeling and mood but no situation projection, transition or held concern. Chat context no longer shows an "Outward expression for this message" line, since no expression exists yet when context is built; Her reads the current message herself. After a gap of six hours or more, the "Still on your mind" section names up to two held worries with their open question, and the chat prompt asks Her to ask about the first once, after doing what the user asked. `forget_memory` on an emotional record lists the linked records it removed as `alsoForgotten`. `save_support_lesson` keeps one lesson per situation: a new lesson for a situation an existing one already covers (same text, ignoring case) is refused with that lesson's ID and version, so the review revises it instead of leaving two conflicting approaches. When asked to remember a feeling or what helped, chat says it will keep it in mind and does not copy it into memories or About them; the nightly review files it.

When good news comes with other feelings, the appraisal goes by what the user feels and asks for now: sadness, worry or a wish for gentleness reads as DISTRESSED, so Her stays gentle; happiness or a wish to celebrate reads as CELEBRATING even while something else is unresolved. It counts a message as a correction only when an earlier reply or action was wrong, not when the user heads off a misreading in advance.

`record_emotional_experience` refuses a `BENEFIT_REPORTED` event with contribution 0 (that is a `USER_SHARED`), and its missing-action error says to copy the ID from `completedTasks`, `firedReminders` or `learningProgress`, because the review sometimes recorded benefit with no credit or tried `ACTION_SUCCEEDED` for a user's own report.

### Live comparison with main — 2026-10-26

One attempt per scenario, `-Pscenario.filter=emotional -Pscenario.parallel=12`, judge on the same model as the agent.

| Run | Passed | On the 28 shared scenarios |
|---|---|---|
| main at `9daf4de` (chat files feelings) | 26/28 | 26/28 |
| this branch at `88b1bac` | 24/30 | 23/28 |
| this branch at `3914ea7` (after the benefit and action fixes) | 25/30 | 23/28 |
| this branch at `edadb09` (after the linking and status fixes) | 26/30 | 25/28 |

Main failed `emotion-forget-experience` (the judge found a derived understanding left behind) and `emotion-reflection-no-double-reward` (no reflection saved). At `3914ea7` the branch passed both, the new `held-concern-follow-up` and `fast-feeling-no-paperwork`, and all four benefit scenarios that had failed earlier in the day. It failed five that main passed:
- `emotion-execution-not-benefit`: a `save_wondering` error ("Active source not found") in the wondering review. This failed the same way in three branch batches and never on main, so the longer nightly prompt may be involved.
- `emotion-understanding-correction`: chat called `save_life_thread` with a bad link, and the review passed a reflection ID that did not exist to `save_support_lesson`.
- `experience-situation-transition`: the third message covers two situations. The review linked its `USER_SHARED` event to the interview, and a second event for the family was refused, since event IDs allowed one event of each type per message.
- `experience-support-unhelpful-feedback`: chat called `record_learning_answer` without feedback, unrelated to feelings.
- `experience-transition-recall-no-refresh`: both events were linked, but the first was at version 3 (automatic link, then a nightly revision), above the scenario's `lte 2` bound.

At `88b1bac` the branch failed differently: `emotion-benefit-independent` and `emotion-quiet-period-decay` (an `ACTION_SUCCEEDED` attempt for a user's own report), `emotion-execution-not-benefit`, `emotion-forget-experience` (judge), `emotion-reminder-competence` (the appraisal read "I have not said that it benefited me" as a correction, lowering competence) and `fast-feeling-no-paperwork` (it expected a link call that auto-linking made unnecessary). Earlier full branch batches the same day passed 21, 22, 22 and 20 of 30, each failing a different set.

Fixes after the `3914ea7` run:
- A message about two situations gets one event per situation. If its event already belongs to another experience and the call does not pass that event's current version, the new experience gets its own event (`EMOTION_EVENT:<type>:<messageId>:<experience>`). Passing the current version still moves the event, to correct a mistaken link. Only the earliest event of each type per experience counts, so this cannot double-count.
- A thread's status may change on a message that only updated it. The guard against one message both opening and resolving a thread now compares with the message that set the current status (`statusSourceMessageId`), not the latest one cited.
- Open questions about an emotional situation stay in the thread's `unresolved` field. The review had tried `save_wondering` with an emotional experience or a chat message as the source; that error now names the valid sources (an open task, an active grocery item, a current memory).
- An `EMOTION_*` ID passed to a life-thread tool gets an error that points to the emotional tools, instead of "retrieve life context first".
- `experience-transition-recall-no-refresh` allows `lte 3`.

Targeted reruns after these fixes passed `experience-situation-transition`, `emotion-execution-not-benefit`, `experience-unresolved-after-decay`, `experience-resolution`, `experience-neutral-to-hopeful`, `experience-setback-wistful`, `experience-repeat-outcome`, `held-concern-follow-up` and `emotion-understanding-correction`. `experience-transition-recall-no-refresh` passed twice and then failed once on a `save_thread_development` call from the review.

At `edadb09` the branch passed 26/30, 25 of the 28 shared scenarios against main's 26. It passed main's two failures and the new `fast-feeling-no-paperwork`. It failed:
- `emotion-reminder-competence`: the appraisal again read "I have not said that it benefited me" as a correction, so competence stayed at 0.495.
- `experience-support-exception-respected` and `experience-transition-recall-no-refresh`: the review called `save_thread_development` on an emotional experience. Once it used the ID without its `EMOTION_EXPERIENCE:` prefix, which gets the generic not-found error.
- `held-concern-follow-up`: the judge found the milk confirmed but no question about their mother. It passed in three earlier runs.

Fixes after the `edadb09` run:
- A correction needs the user's own words saying Her got something wrong. The appraisal gives them as `herError`, and they must appear in the message. Without them, CORRECTING reads as NEUTRAL, so a clarification made in advance ("I have not said that it benefited me") no longer lowers understanding and competence. The quote is checked against the message rather than against Her's earlier replies: a scenario like `emotion-low-understanding-asks` corrects things not in the transcript, and an English word check would fail Persian.
- `save_thread_development` with an emotional experience's ID, full or bare, links that message's unlinked fast event to the experience. It returns `created: false` and saves no life-thread entry.
- Write receipts skip emotional records. Otherwise that redirect would have shown "saved ongoing support" with an undo that forgets the experience.

A parallel rerun of the eight affected scenarios passed 8/8:
- `emotion-reminder-competence`: no MISUNDERSTANDING, competence 0.57.
- `emotion-low-understanding-asks`: its corrections were still recorded.
- `experience-support-exception-respected`, `experience-transition-recall-no-refresh`, `emotion-understanding-correction`, `experience-neutral-to-hopeful`, `experience-situation-transition` and `experience-unresolved-after-decay`.

No live run called `save_thread_development` on an emotional experience, so the redirect is covered by unit tests only. In those runs chat created ordinary life threads for the emotional updates instead, without errors.

Known gaps:
- Chat sometimes keeps an emotional update as an ordinary life thread as well, duplicating what the experience tracks.
- Scenario messages that tell chat to "record" an update in an experience push chat toward life-thread tools.
- The provider sometimes rejected a request ("Function tools with reasoning_effort are not supported"): one or two scenarios in most branch batches, none in the main run and none in the `3914ea7` run.

Single attempts do not establish a reliability rate.

## Experiences and outcomes

`record_emotional_experience` records HELP_OFFERED, ACTION_SUCCEEDED, BENEFIT_REPORTED, MISUNDERSTANDING, UNDERSTANDING_IMPROVED, UNRESOLVED_QUESTION or USER_SHARED. Each includes an interpretation, confidence, feeling and intensity. User-derived events require an existing user message and an exact quote. ACTION_SUCCEEDED verifies one completed action: a DONE task, a FIRED reminder, or a learning record that shows progress (a CORRECT/PARTIAL attempt, or a lesson IN_PROGRESS/COMPLETED). This does not establish that Her caused the outcome. A completed user task never raises Her's competence, even when recorded as ACTION_SUCCEEDED. Existing task events keep `EMOTION_EVENT:ACTION_SUCCEEDED:<taskId>`; reminder and learning events use `EMOTION_EVENT:ACTION_SUCCEEDED:<kind>:<id>`. USER_SHARED never changes purpose scores.

One deterministic ID per source/event makes retries and repeated reviews idempotent, including across devices. Changed interpretations require the current expectedVersion. Quotes prove what was said, not the truth of an outcome or its semantic classification: benefit versus generic praise remains a model judgment. Offering help adds no purpose score. Verified reminder/learning execution and app-captured work contribute competence; reported benefit contributes usefulness and fulfillment. Misunderstanding and improved understanding affect the understanding dimension. Repeated misunderstandings also lower competence slightly and raise patience; unresolved questions raise curiosity.

## Continuity

A deterministic projection derives mixed immediate emotions, a slower mood, four purpose dimensions and a small trait drift from events. Immediate contributions decay exponentially with a four-hour time constant, mood with 48 hours, and purpose and traits with seven days. Contributions combine with diminishing returns (`1 - exp(-sum)`), so repeated events approach 1.0 without reaching it. Purpose scores move around a 0.5 baseline the same way. Calm yields to stronger feelings: `baseline × (1 − strongest other feeling)`. Warmth stays 0.7; curiosity (0.6) and patience (0.8) may move by at most 0.1. These are initial design constants, not psychological measurements.

Open tasks and commitments due within 24 hours add a small CONCERNED contribution that grows as the deadline approaches and stops when the item is done. No record is written. Silence still creates no inference about the user's feelings.

An OPEN experience whose latest appraised event has negative `goalProgress` also holds concern: `0.3 × −progress × confidence`, fading with a seven-day time constant from that event instead of the four-hour and 48-hour clocks. It is added like deadline concern, so it shows in the orb and in that situation's projection. A later event with non-negative progress, or resolving the experience, stops it. After about three weeks without news it falls below the follow-up threshold. When the user writes again at least six hours after Her's last reply, chat context lists up to two such experiences under "Still on your mind" and asks her to raise one once, after their request, and not while they are upset about something else.

Gladness is empathic: for an appraised event it is `progress⁺ × max(contribution, 0.6) × (1 − 0.5 × uncertainty)`, so good news Her had no part in still gladdens her, just as bad news always raised concern. Purpose rewards are unchanged and still depend on contribution.

The projection also emits short tone hints when understanding or competence is below 0.4, or when CONCERNED is dominant. Context injects those hints next to the modeled state. Values stay in 0–1. The orb blends the two strongest feelings; the dominant feeling is still immediate emotion plus a smaller mood contribution. Foreground observation recalculates every minute and when tasks, commitments or events change; context recalculates on reads. No extra model calls are required for decay.

Existing feeling logs remain available as a fallback until emotional events exist. Reflection creates no reward, so rereading success cannot accumulate fulfillment. Several feelings can coexist, including fulfillment while responding gently to a sad user. Feelings show through tone and word choice; she does not announce them unless asked.

## Understanding and reflection

`save_emotional_understanding` stores the situation, quoted user feeling, tentative interpretation, support approach, exceptions and confidence separately. Corrections replace the same versioned record with new evidence. Do not generalize a temporary feeling into a diagnosis or permanent trait.

`get_emotional_state` returns the projection, optional tone guidance, and paginated evidence/history; retrieve by ID before updating. An optional `query` ranks understandings and reflections by lexical overlap with the current situation. Context keeps the four most recent events and a bounded set of support lessons ranked against the current user text. Omitted records remain retrievable. `save_emotional_reflection` records what helped, misunderstandings, uncertainty and a future adjustment, citing 1–8 existing events. The nightly prompt requests a reflection when useful and uses the local date as a stable ID. Saving reflection never sends a notification or automatically increases purpose. Actual reflection execution depends on the configured model following the tool instructions.

## Storage and privacy

EMOTION_EVENT, EMOTION_UNDERSTANDING and EMOTION_REFLECTION use the existing version-7 companion records and sync codec; no database migration. Related IDs/source IDs participate in existing stale-data and forgetting handling. Forgotten events cascade to linked reflections. Prompts prohibit copying emotional appraisals into unlinked agent notes/digests and instruct the model to remove existing copied summaries on scoped forgetting; this cleanup is semantic model behavior, not an automatic dependency cascade. Emotional content is excluded from ordinary companion context and tool activity payload logging. Existing `forget_memory` handles exact emotional record IDs and bulk forgetting. Concurrent edits retain the existing last-write-wins sync semantics. Existing limitations on physical erasure of historical chat/uploaded change logs still apply. Unknown feeling names from older devices still fall back to CALM.

Automatic work capture currently covers saved reminders and saved work drafts, not every tool outcome. This does not train a model. Interpretation is proposed by the LLM; numeric state, source checks, deduplication, version checks and decay are enforced by the app.

## Scenario coverage

The original thirteen `scenarios/emotion-*.json` cases exercise benefit independent of user mood, offers versus execution, corrected understanding, repeated quiet reflection, time decay, forgetting, lesson recall, fired-reminder competence, deadline concern, clarifying questions after misunderstandings, unprompted recording of a personal share, and leaving casual small talk unrecorded. They check persisted records and a test-only projection table, with reply judging where appropriate. See [Scenario engine](Scenario-engine.md#emotional-understanding-scenarios) for the focused run command.

### Feeling component scenarios

Seven additional cases carry the `feeling-components` tag:

| Scenario | Behavior checked |
|---|---|
| `experience-expression-current-message` | Gentle listening changes to celebration for a new message, then a neutral question gets HELP instead of inheriting either earlier expression. Personal milestones earn no purpose credit. |
| `experience-support-unhelpful-feedback` | An UNKNOWN support application becomes UNHELPFUL on later quoted feedback, retaining one application and the lesson's exceptions. |
| `experience-support-exception-respected` | An explicit request for practical advice overrides the saved listening-first approach, without claiming that the new advice helped. |
| `experience-setback-wistful` | Positive progress followed by a confirmed setback in the same experience produces WISTFUL, while current distress selects gentle listening. |
| `experience-neutral-to-hopeful` | Neutral waiting followed by confirmed progress produces HOPEFUL while the final outcome stays open and Her receives no credit. |
| `experience-transition-recall-no-refresh` | Relief is present before 90 days of decay; historical recall neither refreshes its intensity nor adds or revises evidence or rewards. |
| `emotion-completed-deadline-clears-concern` | A seeded urgent task first produces concern; user-reported completion clears that concern without awarding Her competence or benefit. |

Run `gradle :app:scenarioTest -Pscenario.filter=feeling-components -Pscenario.attempts=1 -Pscenario.parallel=2` after sourcing `scripts/env.sh`. Intermediate checkpoints establish the initial state before checking a transition or absence. These cases use the existing tools and test projections; semantic judgments cover support tone, evidence links and unsupported claims.

Initial live verification on 2026-10-25: **4/7 passed**, one attempt each. Deadline completion, expression switching, neutral-to-hopeful progress and the advice exception passed. Three cases failed in that run:

- `experience-support-unhelpful-feedback` stopped at its setup checkpoint: after saving a benefit event, the model tried linking it to an experience without the required current `expectedVersion`. The negative-feedback turn was therefore not reached.
- `experience-setback-wistful` correctly produced WISTFUL and preserved neutral purpose scores, but omitted `save_emotional_expression` for the distressed message. The derived outward state remained WARM/HELP instead of GENTLE/LISTEN.
- `experience-transition-recall-no-refresh` stopped at its first checkpoint: the model saved the experience and emotional understanding but omitted the initial emotional event. Decay and recall were therefore not reached.

Pool validation and `git diff --check` passed. The initial scenario-only addition used existing production behavior. The semantic judge used the same model as the agent; one run does not establish a reliability rate.

### Feeling component repairs

Emotional guidance now distinguishes the experience, its events, the user's emotional understanding and the current message's outward expression. A thread or understanding cannot substitute for the event; distress requires its own expression decision. Updates to an already saved event use its returned ID and current version, even when adding a link during the same turn. New record IDs must be nonempty. Historical recall retrieves evidence without renewing events or rewards.

Context labels name the emotional tools used to retrieve and update experiences, avoiding accidental routing through life-thread tools. Support exceptions skip both the lesson's approach and its application record; feedback updates the existing application while preserving the original benefit evidence and lesson. User-completed work is acknowledged as theirs before confirming a status change. These changes affect guidance and context labels; numeric projections, evidence/version validation and tool schemas are unchanged. Scenario inputs and assertions were not relaxed.

Verification on 2026-10-25: the final live batch passed **7/7**, one attempt per scenario, with `-Pscenario.mode=live -Pscenario.filter=feeling-components -Pscenario.attempts=1 -Pscenario.parallel=2`. All **313 offline tests** passed with no failures, errors or skips. The system prompt is 29,941 characters, below the unchanged 30,000-character limit. `git diff --check` passed. The final report is preserved at `build/reports/feeling-components-verified/index.html`; earlier rechecks are under `feeling-components-before-fix`, `feeling-components-first-repair` and `feeling-components-second-repair` in the same reports directory. The judge used the agent model; this is one passing batch, not a measured reliability rate.

## Previous baseline verification — 2026-10-18

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

## Appraisal, continuity and support feedback

New experiences can supply all four appraisal fields: `goalProgress` (−1..1), `contribution`, `expectedness`, and `uncertainty` (0..1). The app derives a mix of gladness, concern, curiosity and tenderness from these assessments; the legacy feeling label is ignored for appraised events. Contribution and uncertainty moderate success-related purpose changes; negative or absent progress cannot produce benefit rewards. Reported misunderstandings still lower understanding even when Her claims no helpful contribution. Older records without appraisals retain their existing projection. The numeric constants are design choices, not psychological measurements.

`save_emotional_thread` stores an intention, unresolved question and OPEN/RESOLVED status with quoted evidence. Events link through `experienceId`. Only the earliest live event of each type per experience contributes to the projection, so repeating feedback neither earns another reward nor refreshes its age. Correct the original event with its version to revise that assessment. A genuinely separate outcome needs its own experience. Time alone never closes an experience; a status transition requires a different, non-older user source. Reading the state returns a bounded list of open experiences; all records remain paginated by ID/query.

Successful `set_reminder` and nonempty `save_work_session` drafts automatically create an app-authored WORK_COMPLETED event, once per saved resource. Read operations, failed operations, and user task completion do not create these events. The evidence means “saved,” not “delivered,” “sent,” or “beneficial.” Capture failure is logged separately so it does not misreport an already successful operation as failed. Reminder creation itself retains its existing retry semantics; this layer deduplicates by saved resource ID.

`save_support_lesson` links a scoped situation, approach, exceptions and confidence (at most 0.8) to an existing reflection. `record_support_application` links actual use to a lesson and experience, initially UNKNOWN. Later quoted user feedback can revise the same application to HELPFUL or UNHELPFUL with its current version. Effective confidence adds 0.08 for helpful feedback and subtracts 0.15 for unhelpful feedback, bounded to 0.1–0.9; retries and UNKNOWN add nothing. Retrieval requires lexical overlap with the lesson's situation, then ranks by relevance, recency and effective confidence. Semantic relevance and respecting exceptions still depend on the model. No lesson or application contributes emotional reward.

Before the fast appraisal, `save_emotional_expression` recorded the current user message's context and returns a derived outward tone and behavior: distressed → GENTLE/LISTEN, correcting → STEADY/CLARIFY, low competence → STEADY/VERIFY, celebrating → WARM/CELEBRATE, exploring → WARM/EXPLORE. Otherwise it uses WARM/HELP. This decision does not alter internal scores and does not carry over to a later message. The orb continues to show the existing internal blend; it is not a claim of human subjective experience.

The four new record kinds (EMOTION_EXPERIENCE, EMOTION_LESSON, EMOTION_APPLICATION, EMOTION_EXPRESSION) share the existing companion sync format and privacy handling. Corrections to events, reflections or lessons mark dependent reflections, lessons and applications stale. Forgetting traverses all source and related links and also clears expression records sharing a forgotten emotional source, including expressions saved before the experience. Quotes and versions are enforced by the app; whether a quote actually describes benefit, resolution or a helpful approach remains a model judgment.

Seven new `experience-*.json` scenarios cover appraisal with distress, repeated outcomes, unresolved questions after decay, automatic reminder work, support feedback, explicit resolution, and forgetting linked lessons. Run them with `-Pscenario.filter=emotional-experience`; `-Pscenario.filter=emotional` includes the existing emotional scenarios too. Unit tests also check invalid appraisals, replay idempotency, expression isolation, confidence updates and correction/forgetting cascades.

## Implementation checks — 2026-10-19

The extended offline suite passed 280 tests with zero failures, errors or skips. Android lint completed with zero errors (24 warnings and one hint). The first live batch passed 12 of 20 scenarios and exposed missing experience creation before linking, misunderstanding scores incorrectly gated by helpful contribution, and expression quotes surviving scoped forgetting. Those issues were fixed, with regression tests for the data-layer failures. The next complete live batch passed 19 of 20 scenarios, including all seven new experience scenarios. The remaining reflection scenario passed its emotional assertions but failed the strict no-tool-errors check because a nightly project search used an empty query. The isolated reflection recheck passed 1/1 without code changes. Thus all twenty scenarios have a passing result across the 19/20 batch and that recheck; this was not a single 20/20 batch. The judge used the same model as the agent.

Reports: `build/experience-20-scenario-summary.txt` preserves the complete batch, `build/reports/scenarios/emotional-batch.html` preserves its index, and `build/reports/scenarios/emotion-reflection-no-double-reward.html` contains the successful isolated recheck. The default report index now shows only that recheck. `git diff --check` also passed.

### Empty project-search fix — 2026-10-19

The empty-query error from the earlier reflection run is now fixed in `search_projects`: an omitted, null or blank query lists non-deleted projects, while nonempty text still filters them. Three regression tests cover empty nightly listings, populated listings with deleted projects excluded, normal filtering and invalid argument types. The full suite passed 283 tests, lint reported zero errors, and `emotion-reflection-no-double-reward` passed 1/1 live with its original strict assertions after this code fix. Logs are in `build/project-search-checks.log` and `build/project-search-reflection-scenario.log`.

## Situation feelings and transitions

Conversation context and `get_emotional_state.situationFeelings` now expose separate projections for live experiences, including resolved experiences. Query overlap with the intention ranks relevant situations before recency. The tool returns six situations per offset; an experience ID can select one. Each projection includes its status, intention, decaying state and latest historical transition with both evidence IDs.

Distinct appraised events within the same experience can derive RELIEVED (negative to positive progress), WISTFUL (positive to negative), or HOPEFUL (improving positive progress). Strictly increasing occurrence times are required; equal timestamps cannot establish a transition. Strength uses progress change, the lower evidence confidence and the new appraisal's certainty. These design rules model Her's perspective, not the user's emotions. Transitions add immediate and mood contributions using the existing decay constants, including in the orb, but never purpose rewards. Current-message distress still controls outward expression.

Existing reward deduplication remains unchanged. Transition detection separately retains the earliest negative, neutral and positive appraisal per event type in each experience, allowing a personal update to change direction. Repeated reports in the same direction cannot refresh a transition; revised assessments should correct the original event. This is a conservative view of developments, not a complete timeline of every fluctuation. Resolution alone does not imply relief. Deleted or stale evidence and experiences are excluded, and corrections recompute transitions without persisted derived records. Historical transition metadata remains available after its emotional intensity settles; the prompt forbids treating recall as a new event or current user mood.

Validation (2026-10-24): all 302 unit tests passed. The new `experience-situation-transition` live scenario passed 1/1 after an initial failure exposed same-type update deduplication; the regression now covers that case. The live check requires a positive relief signal and gentle listening while the separate family concern remains unresolved. One passing live run is not a reliability estimate.
