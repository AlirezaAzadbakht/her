# Scenario engine

A capability suite, separate from unit tests, that answers whether Her can actually do the things we claim: set a meeting, create a monthly task, remember a fact, and so on. It drives the real `AgentOrchestrator` against a live OpenAI-compatible LLM read from `.env`. That makes it slow and non-deterministic, so it never runs in CI.

Unit tests stay fast and LLM-free (`gradle :app:testDebugUnitTest`). `ScenarioPoolValidationTest` is the only scenario-related test in that suite: it parses every file in the pool and reports unknown tool names without calling a model.

## Workflow

1. Add `scenarios/<id>.json` describing the user turns and the expected outcome. Set `"pending": true` while the tools do not exist yet.
2. Run `make scenarios` (or `make scenario ID=...` for one file). It fails or reports pending until the agent can satisfy the checks.
3. Add or extend tools in `app/src/main/java/com/her/agent/tools/ToolRegistry.kt` and prompt guidance in `Identity.kt`.
4. Rerun until green, drop `"pending"`, commit.

## How to run

```bash
source scripts/env.sh
make scenarios
make scenario ID=calendar-set-meeting
gradle :app:scenarioTest -Pscenario.filter=calendar
gradle :app:scenarioTest -Pscenario.attempts=3 -Pscenario.parallel=1
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

The report is written to `build/reports/scenarios/index.html`, plus `summary.txt` and one HTML page per scenario. Secrets are redacted.

## Scenario JSON

```json
{
  "id": "calendar-set-meeting",
  "title": "Set a meeting in the calendar",
  "tags": ["calendar"],
  "attempts": 2,
  "pending": false,
  "settings": { "chatToolCallLimit": 12 },
  "seed": {
    "profile": { "userName": "Alireza", "assistantName": "Her", "timezone": "Asia/Tehran" },
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

- `turns` are `{"user":"..."}` (enqueue + `processOutbox`) or `{"run":"hourly"|"nightly"|"briefing"}`.
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
| `agent_queue` | id, description, status, priority, dueAt |
| `agent_state` | id, kind, content, confidence |
| `memories_long` | id, content, category, confidence, importance, status, source |
| `memories_short` | id, content, type, confidence, importance, source |
| `profile` | userName, assistantName, timezone, preferredLanguage, country, typicalWakeTime, typicalSleepTime, occupationOrStudyContext |

## What the harness does

Each attempt gets a fresh in-memory Room database, its own `AppSettingsStore` prefs file, a `RecordingToolRegistry`, and a notifier that records instead of posting. `CalendarDataSource.hasPermission()` is false under Robolectric, so calendar tools write the internal `calendar_events` table only.

## Limitations

- No time travel. The production clock is `System.currentTimeMillis()`, so a scenario cannot span simulated days.
- No device calendar or Drive side effects under Robolectric.
- Cost and wall time scale with `attempts` × pool size.
- `RelativeTimeParser` accepts ISO-8601, epoch millis, `next Tuesday at 10am`, and the context-bundle date format. Calendar `time_is` checks compare against that same parser.
