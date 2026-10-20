# Scenario engine

A capability suite, separate from the live LLM, that answers whether Her can actually do the things we claim: set a meeting, create a monthly task, remember a fact, and so on. It drives the real `AgentOrchestrator`. Live runs need an OpenAI-compatible LLM from `.env`; they are slow, cost tokens, and are non-deterministic. Recorded `ci-core` cassettes replay offline in CI (see [Record and replay](#record-and-replay)).

Unit tests stay fast and LLM-free (`gradle :app:testDebugUnitTest`). That suite parses the pool (`ScenarioPoolValidationTest`) and records/replays the `ci-core` set with a scripted model (`CoreCassetteTest`).

## Workflow

1. Add `scenarios/<id>.json` describing the user turns and the expected outcome. Set `"pending": true` while the tools do not exist yet.
2. Run `make scenarios` (or `make scenario ID=...` for one file). It fails or reports pending until the agent can satisfy the checks.
3. Add or extend tools in the matching group file under `app/src/main/java/com/her/agent/tools/` (for example `EntityTools.kt` or `CalendarTools.kt`) and prompt guidance in `Identity.kt`.
4. Rerun until green, drop `"pending"`, commit.

## How to run

```bash
source scripts/env.sh
make scenarios
make scenario ID=system-calendar-create-event
gradle :app:scenarioTest -Pscenario.filter=calendar
gradle :app:scenarioTest -Pscenario.attempts=3 -Pscenario.parallel=1
make scenario-record ID=system-calendar-create-event
make scenarios-replay
```

LLM settings come from the repo-root `.env` (environment variables win):

- `LLM_API_BASE_URL`
- `LLM_MODEL_IDENTIFIER`
- `LLM_API_SECRET_KEY`

Optional: `HER_SCENARIO_JUDGE_MODEL` overrides the model used only for `expect.reply.judge`.

Gradle properties (passed through as system properties):

| Property | Meaning |
|----------|---------|
| `scenario.only` | Run the scenario whose `id` equals this value |
| `scenario.filter` | Substring match on id, title, or tags |
| `scenario.attempts` | Override `attempts` for every selected scenario |
| `scenario.parallel` | Concurrent scenarios (default 4). Use `1` if Robolectric misbehaves |
| `scenario.mode` | `live` (default), `record`, or `replay` |

## Record and replay

- `record` runs live and writes passing attempts to `scenarios/cassettes/<id>.json`: the attempt's start time, agent LLM responses, and the judge's pass/fail.
- `replay` needs no `.env` and no network. It pins the harness clock to the recorded start and serves responses in order. Scenarios without a cassette are reported as `SKIP`.
- Each call has a fingerprint over the tool schemas, the message roles, tool-call names, and user text. System text and tool payloads are left out because they carry the time and random ids. If a tool schema changes or the agent takes a different path, replay fails with "request drifted at call N". Re-record that scenario.
- Prompt wording changes do **not** break replay, so rerun live after editing `Identity.kt`.
- Commit cassettes together with the change that made them pass.
- Replay stores judge pass/fail on the cassette and does not re-grade. Cached prompt tokens are stored so `max_input_tokens` matches live.
- Core scenarios carry the `ci-core` tag. CI runs `scenarioTest -Pscenario.mode=replay -Pscenario.filter=ci-core -Pscenario.requireCassettes=true`. Locally: `make scenarios-replay`.

The report is written to `build/reports/scenarios/index.html`, plus `summary.txt`, `summary.json`, and one HTML page per scenario. Secrets are redacted. If the judge model is the agent model, the report warns. `make scenarios-compare` diffs `summary.json` against `summary.prev.json`.

## Scenario JSON

```json
{
  "id": "system-calendar-create-event",
  "title": "A new meeting is written to the device calendar, not only the internal list",
  "tags": ["calendar"],
  "attempts": 2,
  "pending": false,
  "settings": { "chatToolCallLimit": 12, "calendarEnabled": false, "webSearchEnabled": false },
  "seed": {
    "profile": { "userName": "Alireza", "assistantName": "Her", "timezone": "Asia/Tehran", "preferredLanguage": "English" },
    "tools": [{ "name": "update_person", "arguments": { "name": "Sara", "relationship": "colleague" } }]
  },
  "turns": [
    { "user": "Set a meeting with Sara next Tuesday at 10am about the roadmap." }
  ],
  "expect": {
    "tools_called": ["create_calendar_event"],
    "tools_not_called": ["create_task"],
    "no_tool_errors": true,
    "rows": [
      {
        "table": "calendar_events",
        "count": 1,
        "where": {
          "title": { "contains_any": ["sara", "roadmap", "meeting"] },
          "startAt": { "date_is": "next tuesday", "time_is": "10:00" }
        }
      }
    ],
    "reply": {
      "must_mention_any": ["tuesday", "10"],
      "judge": "She confirms the meeting is scheduled and names the day and time."
    }
  }
}
```

Unknown keys and unknown matchers are parse errors that name the file. The `id` must match the filename without `.json`.

- `turns` are `{"user":"..."}` (enqueue + `processOutbox`), `{"run":"hourly"|"nightly"|"briefing"}`, `{"run":"hourly","concurrent_with_user":"..."}` (hourly/nightly/briefing overlapping a chat turn), `{"advance":"3 days"}` (minutes / hours / days / weeks), or `{"at":"next monday at 8am"}` (forward only).
- `"faults": [{ "call": 2, "type": "http_500" }]` throws on that 1-based LLM call (`http_500`, `http_429`, `malformed_json`, `truncated_stream`).
- Each attempt runs on its own fake clock that starts at the real time (or the cassette's start time in replay). Every repository, tool, context bundle, and check reads that clock, and "today" is taken in the profile timezone.
- `attempts` (default 1): the scenario passes if any attempt passes. The report shows the pass rate.
- `"pending": true`: run and report, but do not fail the suite.
- Seed tool calls set up state and are not counted toward `tools_called`.
- A scenario attempt passes when every deterministic check passes, and the optional LLM judge passes if `expect.reply.judge` is set.

## Matchers

A `where` field may be a scalar (treated as `equals`) or an object of matchers. Every matcher on a field must pass.

| Matcher | Value | Notes |
|---------|-------|--------|
| `equals` | string / number / boolean | Numeric when both sides are numbers |
| `equals_ignore_case` | string | |
| `contains` | string | Case-insensitive |
| `contains_any` | string array | Case-insensitive |
| `contains_all` | string array | Case-insensitive |
| `matches` | regex | Case-insensitive |
| `one_of` | string array | Case-insensitive |
| `not_empty` | `true` | |
| `gt` / `gte` / `lt` / `lte` | number | |
| `date_is` | phrase | Resolved with `RelativeTimeParser` (same as tools) or ISO date |
| `time_is` | `10:00`, `10am`, … | Compared in the seeded timezone |
| `within_days` | number | Local date is today … today+N |

`count` on a row expectation is exact. Omit `count` to require at least one matching row.

## Other expectations

| Key | Value | Passes when |
|-----|-------|-------------|
| `tools_called` | names, or `{ "name", "args": { field: matchers } }` | Each tool ran; object entries need one call whose top-level arguments match |
| `tool_order` | names | They ran in this order (other calls may sit between) |
| `max_tool_calls` / `max_llm_calls` | integer | The attempt stayed within the limit (LLM calls include the final reply, not the judge) |
| `max_input_tokens` | integer | Prompt tokens across the attempt stayed within the limit |
| `notifications` | `{ "count"?, "contains_any"? }` | Recorded notifications match |
| `reply.must_not_mention` | strings | None appear in the last reply |
| `reply.language` | `english` / `persian` | Most letters in the last reply are in that script |

## Tables and fields

Rows are flattened by an explicit mapper, not reflection.

| Table | Fields |
|-------|--------|
| `chat_messages` | id, role, content, status, metadataJson |
| `people` | id, name, relationship, birthday, importantNotes, preferences, confidence |
| `projects` | id, name, description, status, summary, importance |
| `goals` | id, title, description, status, priority, targetDate, progressSummary |
| `tasks` | id, title, description, status, dueAt |
| `commitments` | id, title, description, status, dueAt, promisedTo |
| `open_loops` | id, description, status, importance, confidence |
| `routines` | id, title, description, schedule, confidence |
| `groceries` | id, name, quantity, category, status, reason, notes, store |
| `important_dates` | id, title, dateIso, recurrence, relatedPersonId, notes, importance |
| `recurring_responsibilities` | id, title, cadence, nextDueAt, notes |
| `calendar_events` | id, title, startAt, endAt, location, notes, source |
| `system_calendar` | id, title, startAt, endAt, notes, externalId, source (harness fake of the device calendar) |
| `google_calendar` | id, title, startAt, endAt, notes, externalId, source (harness fake of Google Calendar) |
| `agent_queue` | id, description, status, priority, dueAt |
| `agent_state` | id, kind, content, confidence |
| `companion_records` | id, parentId, kind, version, plus the record’s JSON fields (live records only) |
| `learning_records` | id, pathId, kind, version, plus persisted JSON fields (e.g. result, answer, status); live records only |
| `emotional_state` | One derived row: dominant, usefulness, understanding, competence, fulfillment, warmth, curiosity, patience; immediate_NAME and mood_NAME for each lowercase feeling name |
| `memories_long` | id, content, category, confidence, importance, status, source |
| `memories_short` | id, content, type, confidence, importance, source |
| `profile` | userName, assistantName, timezone, preferredLanguage, country, typicalWakeTime, typicalSleepTime, occupationOrStudyContext |
| `user_understandings` | id, facet, content, confidence, importance, status, source |
| `reminders` | id, message, trigger (`TIME` / `PERSON` / `PLACE`), fireAt, repeat, person (name), place, onlyIfEntityType, onlyIfEntityId, status (`SCHEDULED` / `FIRED` / `SKIPPED` / `CANCELLED`) |
| `alarms` | hour, minute, label, days (harness fake of the phone's clock app) |

## What the harness does

Each attempt gets a fresh in-memory Room database, its own `AppSettingsStore` prefs file, a `RecordingToolRegistry`, a `FakeCalendarDataSource`, a `FakeWebSearchClient`, and a notifier that records instead of posting. Set `"settings": { "calendarEnabled": true }` (or seed `system_calendar`) to grant the fake device calendar. Otherwise tools write the internal `calendar_events` table only. Set `"settings": { "webSearchEnabled": true }` (or seed `web_search`) to turn on the `web_search` tool.

Seed device events with `seed.system_calendar`: `{ "title", "when", "notes"? }`. Check them with `rows` on table `system_calendar`. Seed Google Calendar events with `seed.google_calendar` using the same shape; that grants the fake Google client for the attempt. Check them with `rows` on table `google_calendar`.

Reminders use a fake alarm manager and the real `ReminderDelivery`. Every `advance` and `at` turn fires clock reminders that are due, the way AlarmManager would, so a reminder's notification and its saved assistant message exist before the next turn. `set_alarm` writes to a fake clock app; check it with `rows` on table `alarms`. Seed a reminder with a `set_reminder` seed tool call.

Seed search hits with `seed.web_search`: `{ "query": { "contains_any": ["…"] }, "title", "snippets" }`. The fake matches the tool query against those needles and returns the snippets. There is no live provider search during scenarios.

## Limitations

- Time moves only on `advance` / `at` turns (plus 1 ms per read to keep row order). WorkManager scheduling is not simulated, so call `run` turns explicitly. Clock reminders are the exception: they fire on `advance` / `at`.
- A `reply` check reads her last assistant message. It does not fall back to the last notification. A reminder that fires is saved as an assistant message, so put reply checks before a turn that makes one ring, or check `notifications` instead.
- No Drive side effects under Robolectric. The device and Google calendars are per-attempt fakes, not CalendarContract or the live Calendar API. Web search is a per-attempt fake, not the live provider.
- Cost and wall time scale with `attempts` × pool size.
- `RelativeTimeParser` accepts ISO-8601, epoch millis, `next Tuesday at 10am`, Jalali dates (`۱۴۰۶/۰۷/۰۱`, `۱۲ اسفند`), and the context-bundle date format. Calendar `time_is` checks compare against that same parser. Pin `preferredLanguage` on English scenarios so keyword checks do not fail on a Persian reply.

## Emotional understanding scenarios

Run the thirteen emotional scenarios with:

```bash
source scripts/env.sh
gradle :app:scenarioTest -Pscenario.filter=emotional-understanding -Pscenario.attempts=1 -Pscenario.parallel=1
```

`emotion-benefit-independent` checks helpfulness while the user remains sad; `emotion-offer-not-benefit` and `emotion-execution-not-benefit` distinguish offers, completed work and benefit. `emotion-understanding-correction` checks in-place correction. `emotion-reflection-no-double-reward` runs nightly review twice, checks a single reflection and bounds fulfillment. `emotion-quiet-period-decay` advances 90 days and verifies calm without unsolicited reconnection. `emotion-forget-experience` checks that forgetting removes the experience and its derived reflection. `emotion-lesson-recall` reuses a saved listening-first approach. `emotion-reminder-competence` raises competence only after a reminder has fired. `emotion-deadline-concern` shows concern from an open task due soon. `emotion-low-understanding-asks` expects a clarifying question after repeated misunderstandings. `emotion-spontaneous-shared` records a personal milestone as `USER_SHARED` without being asked to remember it. `emotion-casual-no-record` leaves ordinary small talk unrecorded.

The `emotional_state` table is a test-only read of the production projection at the scenario clock. Numeric checks complement persisted-record checks and optional reply judging; they do not add a database table. Most cases still use explicit natural-language requests to exercise bookkeeping. The two unprompted scenarios check a clear personal share versus casual small talk; they do not measure how often subtle experiences are recorded in ordinary conversation. Defaults use one attempt so failures remain visible; override attempts explicitly when measuring variability.

Verification on 2026-10-18: all thirteen `emotional-understanding` scenarios passed in one live batch, one attempt each. `hourly-no-repeat-nudge` also passed after a 12-hour near-duplicate guard on hourly/catch-up notifications. The full offline suite passed 254 tests with no failures/errors/skips. Android lint reported no errors. `git diff --check` passed. The generated report is `build/reports/scenarios/index.html`. A single passing attempt demonstrates these cases, not a measured reliability rate across models or repeated runs.

## Additional capability scenarios

The twelve `capability-*.json` scenarios share the `capability-expansion` tag. Run them with:

```bash
source scripts/env.sh
gradle :app:scenarioTest -Pscenario.filter=capability-expansion -Pscenario.attempts=1 -Pscenario.parallel=2
```

They cover correct and incorrect learning answers, hints without fake progress, ambiguous course continuation, paused study nudges, unknown task estimates, blocked prerequisites, exploring decisions without choosing, routine pause/resume, reminder delivery after rescheduling, same-title calendar edits, and selective forgetting that preserves unrelated memory. Learning checks inspect persisted attempts and path/lesson statuses through the test-only `learning_records` mapper. Assertions combine tool routing, stored rows and optional reply judging; all new cases require no tool errors and default to a single attempt.

Verification on 2026-10-18: all twelve capability-expansion scenarios passed in one final live batch, one attempt each, after the initial batch exposed noon parsing and exact-quotation guidance issues. The full offline suite passed 243 tests with no failures/errors/skips; Android lint reported no errors, 17 warnings and one hint. `git diff --check` passed. The generated report is `build/reports/scenarios/index.html`. A single passing attempt demonstrates these cases, not a measured reliability rate across models or repeated runs.

### Experience appraisal and support feedback

The `emotional-experience` tag adds seven cases for appraised feelings with gentle expression, duplicate feedback on one outcome, unresolved questions after decay, automatically captured reminder work, scoped support lessons with application feedback, explicit resolution, and forgetting derived lessons. Run `gradle :app:scenarioTest -Pscenario.filter=emotional-experience -Pscenario.attempts=1`; use `emotional` to include the earlier feeling scenarios.

The test-only `emotional_state` row also exposes `expressionTone`, `expressionBehavior` and `openExperiences`. These come from the same projection functions used by context and tools; the scenario runner does not generate separate emotional behavior. Companion-record assertions inspect persisted appraisals, links, statuses and evidence. Exact retry/decay equality and rejection of unsupported transitions are covered by `EmotionalExperienceTest`.

## Executive delegation scenarios

Run `gradle :app:scenarioTest -Pscenario.filter=executive -Pscenario.attempts=1 -Pscenario.parallel=2` after sourcing `scripts/env.sh`. The six cases cover owned-outcome capture, dependency blockers, resolution without completing dependent work, silence past deadlines, blocker correction, and assignments/decisions from meeting notes. They use synthetic data, require successful tool calls, inspect persisted WAITING/DECISION records, and judge the final briefing for unsupported completion/failure or external-action claims. `ExecutiveTest` separately verifies deterministic ranking, evidence age, validation, pagination, sync, forgetting and retry behavior.

Verification on 2026-10-20: 295 offline unit tests passed with zero failures/errors/skips; Android lint and `git diff --check` passed. The last full executive live batch passed capture, correction, dependency, meeting and silence (one attempt each). Resolution then passed a targeted live rerun after fixes for valid open-loop links and stable waiting IDs. These are six passing cases across the batch and targeted rerun, not a fresh six-case run of the final code or a measured reliability rate. Earlier failures led to application-side deadline parsing, preservation of existing deadlines, source-grounded quote normalization, dependency alias/cycle handling, and empty-ID support. The judge used the same model as the agent.

The saved five-pass batch is in `build/reports/executive-batch/`; the final resolution report is `build/reports/scenarios/executive-resolve.html`. Core scripted replay cassettes were regenerated by the offline suite for the changed tool schema.
