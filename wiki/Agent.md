# Agent

The model never talks to SQL. It sees a system prompt, a structured context bundle, recent messages, and a tool list. It proposes function calls. The app runs them.

## Loop

`AgentOrchestrator` in `app/src/main/java/com/her/agent/runner/Runners.kt`:

1. `ContextBuilder` assembles messages.
2. `LlmClient` POSTs to `{baseUrl}/chat/completions` with OpenAI-style `tools` and `tool_choice: auto`. Chat turns stream (`stream=true`); hourly / nightly / briefing stay blocking. User lines are stored as `PENDING` and `processOutbox()` joins the whole batch into one turn. `web_search` is omitted from the tool list unless Settings → Web search is on; that tool then makes a separate, tool-free completion with `web_search_options`.
3. Each tool call is validated and executed by `ToolRegistry`.
4. Tool results go back into the thread.
5. Repeat until the model stops calling tools or the **call budget** is exhausted.

The orchestrator logs prompts, retrieved memories, raw LLM JSON, and tool I/O to `debug_events`, and token usage per run type to `api_usage`.

## Context

`ContextBuilder` (`app/src/main/java/com/her/agent/prompt/ContextBuilder.kt`) builds:

1. `Identity.SYSTEM_PROMPT`
2. Optional extra system text (hourly / nightly / briefing)
3. A bundle: now, profile, **About them** (active `user_understandings`, always, cap 16), retrieved memories (cap 12), people, projects, goals, **tasks as `id \| title [status]`**, commitments, open loops, routines, groceries, dates, internal / system / Google calendar (7-day window; live sources stay visible even when empty), agent state and queue
4. Recent chat (default 24 messages)

Task lines include ids so `update_task` can target a row. Titles alone used to fail when the model invented a status like `cancelled`.

## Call budgets

| Run | Max LLM calls | Extra rules |
|-----|---------------|-------------|
| Chat | `chatToolCallLimit` (default 12, min 1) | User-driven |
| Hourly / catch-up | 10 | Skip if last **successful** hourly was &lt; 20 minutes ago; catch-up if the gap is &gt; 3 hours. Quiet hours skip the LLM pass unless the Settings toggle is on. |
| Nightly | 50 | Once per calendar day |
| Briefing | 8 | Once per calendar day |

## Tools

Defined in `app/src/main/java/com/her/agent/tools/ToolRegistry.kt`. Groups:

- Time and memory: `get_current_time`, `search_memory`, `remember`, `update_memory`, `forget_memory`, `search_chat_history`, `relate_memories`
- People and projects: `get_person`, `search_people`, `update_person`, `get_project`, `search_projects`, `update_project`
- Goals / tasks / commitments / open loops: get / create / update (plus `close_open_loop`)
- Routines, groceries, important dates, recurring responsibilities
- Agent internals: `get_agent_state`, `update_agent_state`, queue add / update / complete
- `update_user_profile`, `update_user_understanding`
- Calendar get / create / update / delete (`get_calendar_events` takes `from` / `to` / `days` for any slice and returns internal, system, and Google; Google rows are read-only)
- `web_search`, `send_user_message`

`requireTask` resolves by **id or title**. Status strings go through `parseEnum` with aliases (`cancelled` → `DROPPED`, `bought` → `PURCHASED`, and so on).

Destructive actions that need confirmation: `forget_memory(everything=true)`, `delete_calendar_event` on an external event.

## LLM settings

`LlmSettings.isConfigured` requires a non-blank base URL, API key, and model. `ping()` is the setup health check.
