# Implementation Plan

AgentKit is delivered in **11 phases**, each shipped as its own pull request with
**at least three independent reviews** whose findings are fixed before merge.
Every phase keeps `main` green (compiles + tests pass) and builds only on the
SPIs established by earlier phases.

## Module layout (target)

```
./                          # parent POM (agentkit-parent): dependency & plugin management
├── agentkit-core/          # provider-agnostic core (no vendor SDKs)
├── agentkit-llm-anthropic/ # LlmClient adapter over the Anthropic Java SDK
├── agentkit-temporal/      # durable execution: agent loop as a Temporal workflow
└── agentkit-examples/      # runnable end-to-end demos
```

Modules are introduced by the phase that first needs them, so each PR stays
focused and buildable.

## Phase breakdown

### Phase 0 — Scaffolding, research & plan  *(this PR)*
- Maven parent + `agentkit-core`, CI, coding conventions.
- `RESEARCH.md`, `PLAN.md`, `README.md`.
- Foundational domain model: `Message`/`ContentBlock`/`Conversation`,
  `Goal`/`AgentResult`/`StopReason`.
- **Exit:** `mvn test` green; docs reviewed.

### Phase 1 — Core agent loop + LLM abstraction
- `LlmClient` SPI (`LlmRequest`/`LlmResponse`), `Tool` SPI + `ToolResult`.
- The core **agent loop**: prompt → model → tool calls → results → repeat until
  stop, with step/budget limits and a pluggable stop condition.
- `agentkit-llm-anthropic` adapter (`claude-opus-4-8`).
- **Exit:** loop drives a fake `LlmClient` end-to-end in tests; Anthropic adapter
  compiles and maps the content-block model.

### Phase 2 — Progressive tool disclosure
- `ToolRegistry` with per-tool `deferLoading`.
- BM25 `ToolSearch` and a built-in `tool_search` tool that reveals deferred tools
  on demand (append-only, cache-friendly).
- **Exit:** an agent with 100 deferred tools loads only the searched few.

### Phase 3 — Skills subsystem
- `SKILL.md` parser (YAML frontmatter: `name`, `description`, body + resources).
- `SkillRegistry` implementing three-tier progressive disclosure (metadata →
  body → bundled files) exposed to the agent as a skill tool.
- **Exit:** skills load in tiers; only triggered skills consume body tokens.

### Phase 4 — Knowledge base + retrieval
- `KnowledgeBase` SPI + `Retriever`; `Document`/`Chunk`/`SearchResult` model.
- In-memory BM25 retriever and an `EmbeddingStore` interface for vector search.
- A `knowledge_search` tool the agent can call.
- **Exit:** agent retrieves relevant chunks from a KB via the tool.

### Phase 5 — Memory subsystem
- `WorkingMemory` (in-session scratchpad) + `MemoryStore` SPI (cross-session).
- File-backed store with path-traversal-safe access; a `memory` tool.
- **Exit:** an agent recalls a fact written in a previous run.

### Phase 6 — Context engineering
- `ContextBuilder` assembling system + skills + memory + KB + history.
- `TokenBudgeter`, `CompactionStrategy`, `ContextEditingStrategy`.
- **Exit:** long conversations stay within budget via compaction/editing.

### Phase 7 — Verification & reliability
- `Verifier` SPI + self-verification pass; guardrail/action-gating hooks on tool
  execution; structured-output validation; retry-with-backoff policy.
- **Exit:** a failing verification triggers a bounded recovery loop; gated
  actions require approval.

### Phase 8 — Supervisor + subagents
- `Supervisor` orchestrator; subagent roster; delegate/collect; parallel fan-out
  over independent subgoals with result handoff.
- **Exit:** a supervisor decomposes a goal, runs subagents, and synthesizes.

### Phase 9 — Temporal durable execution
- `agentkit-temporal`: agent loop as a **Workflow**; LLM inference and tool
  execution as **Activities** with retry policies; signals/queries for steering
  and observability.
- **Exit:** the same loop runs durably; a simulated failure replays without
  re-executing completed activities.

### Phase 10 — Integration, examples & docs
- `agentkit-examples`: an end-to-end agent wiring every subsystem; a Temporal
  worker example.
- Final architecture docs and diagrams.
- **Exit:** the example runs against a fake LLM in tests; docs complete.

## Definition of done (per phase)

1. Code compiles; `mvn test` passes.
2. New behavior is unit-tested (fakes for anything requiring a network).
3. PR opened; **≥ 3 independent reviews** performed.
4. Review findings triaged and fixed (or explicitly deferred with rationale).
5. Public API is documented with Javadoc; `PLAN.md` checkbox ticked.

## Progress

- [x] Phase 0
- [x] Phase 1
- [x] Phase 2
- [x] Phase 3
- [x] Phase 4
- [x] Phase 5
- [x] Phase 6
- [ ] Phase 7

### Deliberate scope decisions

- **Working-memory injection** into the engineered context is a *caller
  responsibility* (via `WorkingMemory.render()` or the `remember`/`recall`
  tools), not automatic in the loop — keeps the `ContextStrategy` seam a pure
  history transform.
- **Memory tool command set** is `read`/`write`/`append`/`delete`/`list` for v1;
  partial edits (`str_replace`/`insert`) are a future addition.
- **Compaction issues a model call**, so under the Temporal integration (Phase 9)
  the context strategy runs inside an activity, not the replayed workflow body.
- [ ] Phase 8
- [ ] Phase 9
- [ ] Phase 10
