# Wondering

Her keeps tentative questions in the separate `wondering` table, visible in the SQL Memory browser and included in Drive sync. They are not facts about the user.

Hourly reviews inspect tasks overdue by at least a day, purchases still active after seven days, and a rotating daily sample of four durable memories. These ages select review candidates; they do not prove a problem or justify a notification. She checks existing explanations before saving at most one useful question. Nightly reviews examine related memories and answers more deeply and can save up to three questions. These review limits are model instructions; candidate windows and question cooldowns are enforced in code.

A source has one wondering. Each stores an observation, question, source snapshot, timestamps, version and status: OPEN, ASKED, RESOLVED, DISMISSED or OBSOLETE. Completed, historical, expired or removed sources become obsolete on review. Corrections invalidate linked questions and return their current status and version so the agent does not edit stale questions. Forgetting a source or answer also removes the associated wondering; tombstones prevent sync from restoring it.

Questions wait for a relevant conversation. `ask_wondering` delivers one saved question, at most once every three days across all wonderings; background runs cannot use it. The tool accepts the saved question ID or its source ID, with the same version and cooldown checks. Context labels source types so task and purchase changes use their own tools. An unanswered question is never automatically asked again. Refusals are dismissed with user evidence. Resolution requires a real user message and an exact quote. A question does not itself change the linked task or purchase.

The agent uses confirmed answers to revise existing user-understanding facets. A stated preference can be remembered directly; inferred patterns need multiple independent examples, uncertainty and exceptions. Silence and age are not evidence of motives or personality.

Database version 8 adds the table through migration 7→8 without changing existing records.

Sixteen live-model scenarios and intermediate-state checks are documented in [Scenario engine](Scenario-engine.md#wondering-scenarios). Run with `-Pscenario.filter=wondering`.
