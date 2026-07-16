# AgentKit Architecture

AgentKit is a Java framework for **reliable, unsupervised, tool-using agents**.
This document explains how the pieces fit together. For the phased build history
see [`PLAN.md`](PLAN.md); for the design rationale see [`RESEARCH.md`](RESEARCH.md).

## Design tenets

1. **Provider-agnostic core.** `agentkit-core` depends on no vendor SDK. All model
   specifics sit behind the `LlmClient` SPI, so the whole framework is unit-testable
   with fakes and portable across providers.
2. **Everything is progressive.** Tools, skills, and context disclose in tiers so a
   large capability surface never floods the model's context window.
3. **Verify before trusting.** Reliability — verification, gating, retries — is a
   first-class subsystem, not an afterthought.
4. **Durability is an integration.** The same loop runs in-process or as a Temporal
   workflow; the core never imports Temporal.

## Modules

```
agentkit-core/          provider-agnostic core: the loop + every subsystem
agentkit-llm-anthropic/  LlmClient over the Anthropic Java SDK
agentkit-llm-bedrock/    the Anthropic adapter on Claude via Amazon Bedrock
agentkit-temporal/       the loop as a Temporal workflow (durable execution)
agentkit-examples/       runnable end-to-end demos wiring it all together
```

The Anthropic adapter carries a `ModelResolver` seam that translates the logical
model id on each request into the provider's wire id, so `agentkit-llm-bedrock`
reuses the adapter (adding only the Bedrock backend and
application-inference-profile discovery — an AWS control-plane call that maps
logical ids → account-specific profile ARNs). The seam defaults to identity, so
the first-party path is unaffected.

Only `agentkit-core` is required; the others are opt-in integrations. Dependencies
flow one way: adapters and integrations depend on core, never the reverse.

## The agent loop

The heart is a bounded goal→result loop (`core.agent.Agent`):

```
Goal
 │
 ▼
┌─────────────────────────────────────────────────────────────┐
│  while steps < maxSteps:                                     │
│    1. ContextStrategy.prepare(history)   ← edit / compact    │
│    2. LlmClient.generate(request)        ← model turn        │
│    3. on stop reason:                                        │
│         END_TURN, no tools  → COMPLETED                      │
│         tool_use            → run tools, append results, loop│
│         REFUSAL→REFUSED, PAUSE→PAUSED, MAX_TOKENS→TRUNCATED  │
│    4. each tool: ToolGate.evaluate → Tool.execute           │
└─────────────────────────────────────────────────────────────┘
 │
 ▼
AgentResult (stopReason, output, steps, usage)
```

A single tool failure never aborts the run — a thrown tool (or a gate denial)
becomes an error `ToolResult` the model can react to. The loop depends only on
`LlmClient` and `ToolRegistry`, which is what lets the *same* logic run durably
under Temporal.

## Subsystems (all in `agentkit-core`)

| Subsystem | Package | Key types | What it provides |
| --- | --- | --- | --- |
| **Messages** | `message` | `Message`, sealed `ContentBlock` | Provider-agnostic content model. |
| **Tools** | `tool` | `Tool`, `ToolRegistry`, `DisclosingToolRegistry` | Tool calling + progressive disclosure (deferred tools revealed via BM25 `search_tools`). |
| **LLM SPI** | `llm` | `LlmClient`, `LlmRequest`, `LlmResponse` | The one seam every provider implements. |
| **Skills** | `skill` | `Skill`, `SkillLibrary`, `Skills` | `SKILL.md` skills with three-tier disclosure (name → body → resources). |
| **Knowledge** | `knowledge` | `KnowledgeBase`, `Retriever`, `KnowledgeTools` | RAG mechanism: chunking + BM25 / vector retrieval exposed as `knowledge_search`. Data & embeddings are yours. |
| **Memory** | `memory` | `MemoryStore`, `WorkingMemory`, `MemoryTools` | Durable cross-session memory + per-run scratchpad, confined to a root (no traversal). |
| **Context** | `context` | `ContextStrategy`, `ClearToolResultsEditor`, `SummarizingCompactor` | Keeps long runs within budget: edit old tool results, then summarise. |
| **Reliability** | `reliability` | `RetryingLlmClient`, `ToolGate`, `ToolGates` | Backoff retries + action gating for hard-to-reverse tools. |
| **Verification** | `verify` | `Verifier`, `LlmVerifier`, `SelfVerifyingAgent`, `Verifiers` | Don't trust the first answer: an independent critic must pass, else retry with feedback. |
| **Supervision** | `supervisor` | `Supervisor`, `Subagent`, `Synthesizer` | Decompose a goal across subagents (parallel fan-out or model-driven `delegate`), then synthesise. |

### How they compose

The subsystems are orthogonal seams, wired at construction (see
`agentkit-examples/EndToEndAgent`):

```
              ┌──────────────── SelfVerifyingAgent ────────────────┐
              │  runs a fresh Agent per attempt; critic must pass  │
              └───────────────────────┬────────────────────────────┘
                                      │
          ┌───────────────────────── Agent ─────────────────────────┐
          │  LlmClient (RetryingLlmClient → Anthropic)               │
          │  ContextStrategy (edit + compact)                        │
          │  ToolGate (deny destructive)                             │
          │  ToolRegistry (DisclosingToolRegistry):                  │
          │     always: knowledge_search, memory, remember, recall   │
          │     deferred: order_lookup, delete_document (gated)      │
          └──────────────────────────────────────────────────────────┘
```

A `Supervisor` sits *above* this: each `Subagent` is itself an `Agent` (built
fresh per delegation), and the supervisor fans subgoals out and synthesises.

## Durable execution (`agentkit-temporal`)

The durable path reimplements the loop as a Temporal **workflow**, delegating the
two non-deterministic / side-effecting operations to **activities**:

```
        Workflow (deterministic, replayed from history)
        ─ conversation, step & usage counters, control flow ─
             │  LlmCallSpec              │  ToolInvocation
             ▼                           ▼
      ┌─────────────┐            ┌──────────────┐     Worker process
      │ LlmActivity │            │ ToolActivity │     (holds the real
      │  → LlmClient│            │  → ToolReg.  │      client & tools)
      └─────────────┘            └──────────────┘
```

- The workflow holds only replayable state plus the serializable run config and
  tool specs; the model client and tools live in the worker's activity impls.
- A completed activity is memoized in history and never re-run on replay — proven
  in-memory (no server) by forcing replay with the sticky cache disabled.
- Core stays serialization-annotation-free: the one polymorphic type, the sealed
  `ContentBlock`, is taught to Jackson by a mix-in in the Temporal module's
  `DataConverter`.

**v1 scope:** the durable loop uses a fixed tool set (progressive disclosure would
need the revealed set tracked as durable state), and context strategy / gating /
verification wrap the loop rather than living inside it. Activity retry is
at-least-once, so non-idempotent tools should set `toolMaxAttempts = 1`.

## Testing strategy

Every subsystem is unit-tested against fakes — no network in the test suite. The
`LlmClient` seam makes the entire loop (in-process and durable) exercisable with a
scripted fake model; the Temporal tests run in the SDK's in-memory environment.
`agentkit-examples` carries integration tests that drive the fully-wired agent and
supervisor end-to-end against a fake, proving the subsystems compose.
