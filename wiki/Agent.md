# Agent

The model never talks to SQL. It sees a system prompt, a structured context bundle, recent messages, and a tool list. It proposes function calls. The app runs them.

## Loop

`AgentOrchestrator` in `app/src/main/java/com/her/agent/runner/Runners.kt`:

1. `ContextBuilder` assembles messages.
2. `LlmClient` POSTs to `{baseUrl}/chat/completions` with OpenAI-style `tools` and `tool_choice: auto`. Chat turns stream (`stream=true`); hourly / nightly / briefing stay blocking. User lines are stored as `PENDING` and `processOutbox()` joins the whole batch into one turn. `web_search` is omitted from the tool list unless Settings → Web search is on; that tool then makes a separate, tool-free completion with `web_search_options`.
3. Each tool call is validated and executed by `ToolRegistry`.
4. Tool results go back into the thread.
5. Repeat until the model stops calling tools or the **call budget** is exhausted.

The orchestrator logs prompts, retrieved memories, raw LLM JSON, per-call token usage (kind `usage`, including prompt tokens the provider served from cache), and tool I/O to `debug_events`, and token usage per run type to `api_usage`.

## Context

`ContextBuilder` (`app/src/main/java/com/her/agent/prompt/ContextBuilder.kt`) builds:

1. `Identity.SYSTEM_PROMPT`
2. Optional extra system text (hourly / nightly / briefing)
3. A bundle, ordered so the slow-changing part forms a stable prefix that providers can cache:
   - Stable: profile, **About them** (active `user_understandings`, always, cap 16), web-search flag, people, projects, goals, routines, dates, **Recent days** (the `digest` note)
   - Volatile: now, when their last message was sent, retrieved memories (cap 12), **tasks as `id \| title [status]`**, commitments, open loops, groceries, internal / system / Google calendar (7-day window; live sources stay visible even when empty), agent state and queue
4. Recent chat (default 24 messages)

Task lines include ids so `update_task` can target a row. Titles alone used to fail when the model invented a status like `cancelled`.

The nightly pass rewrites one `update_agent_state(kind="digest")` note about the last several days: decisions, what she did, threads to pick up. `update_agent_state` upserts by kind for `digest`, and the bundle always shows it, so a decision survives after it scrolls out of the 24-message window.

## Retrieval

`HybridRanker` runs FTS4 first. `ftsQuery` in `Core.kt` turns a sentence into `word* OR word* …` (up to 8 words, stopwords dropped, English and Persian), so any shared word can match. Tokens are Unicode-aware and normalized: Arabic ي/ك fold to Persian ی/ک, diacritics and tatweel are dropped, ZWNJ splits words, and Persian/Arabic digits become ASCII. The same `tokenize` feeds the lexical-overlap score. Expired short-term memories are excluded from FTS hits.

When Settings → **Embeddings** is on, `OpenAiEmbeddingProvider` calls `{baseUrl}/embeddings` with the configured embedding model (default `text-embedding-3-small`), using the same key as chat. The ranker then scores a wider pool: FTS hits plus up to 200 active memories of each type. For those it blends cosine similarity into the semantic part of the score (35% lexical, 65% cosine). Vectors are cached in the device-local `memory_embeddings` table, keyed by memory id, model, and a content hash. Up to 64 missing ones are embedded per search, in the same request as the query. Any embeddings failure is logged and the search falls back to lexical ranking.

## Write receipts

Each successful user-visible write in a turn becomes a `Receipt` (`app/src/main/java/com/her/agent/runner/Receipts.kt`), stored in the assistant message's `metadataJson`. Private bookkeeping (agent state, queue, `update_user_understanding`) is not announced. Records she just created are undoable through `ToolRegistry.undo(entityType, id)`. Updates and upserts of existing rows (`add_grocery` on an item already listed, `update_person`) are shown but not undoable.

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

`requireTask` and `requireCommitment` resolve by **id or title**. `update_task` and `update_commitment` take `dueAt` to move a deadline without dropping and recreating the row. A `dueAt` that does not parse is an error, not a silent no-op. Status, scope, source, and facet fields list their allowed values as JSON-Schema `enum`. Status strings still go through `parseEnum` with aliases (`cancelled` → `DROPPED`, `bought` → `PURCHASED`, and so on).

Destructive actions that need confirmation: `forget_memory(everything=true)` (deletes short- and long-term memories and archives the About them rows), `delete_calendar_event` on an external event.

## LLM settings

`LlmSettings.isConfigured` requires a non-blank base URL, API key, and model. `ping()` is the setup health check.
