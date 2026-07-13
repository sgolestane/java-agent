# AgentKit

A Java framework for building **reliable, unsupervised, tool-using AI agents**.

AgentKit gives a goal-driven agent the machinery it needs to run on its own:
progressive disclosure of tools and skills, a pluggable knowledge base, working
and long-term memory, deliberate context engineering, verification of its own
actions, and supervisor/subagent orchestration — with an optional **Temporal**
integration for durable execution.

> Status: **early development.** See [`docs/PLAN.md`](docs/PLAN.md) for the
> phased roadmap and [`docs/RESEARCH.md`](docs/RESEARCH.md) for the design
> rationale.

## Why

Agents that run unsupervised fail in two characteristic ways: their quality rots
as the transcript grows, and they confidently take wrong, irreversible actions.
AgentKit is organized around preventing both — context is engineered rather than
dumped, and actions are verified and gated rather than trusted.

## Capabilities

| Capability | What it does |
| --- | --- |
| **Goal-driven agent loop** | Pursues a `Goal` to completion with step/budget limits. |
| **Progressive tool disclosure** | Keeps large tool libraries out of context; reveals tools on demand via search. |
| **Skills** | `SKILL.md`-based skills with three-tier progressive disclosure. |
| **Knowledge base** | Pluggable retrieval (BM25 + vector SPI) the agent can query. |
| **Memory** | In-session working memory + durable, cross-session memory store. |
| **Context engineering** | Token budgeting, compaction, and context editing. |
| **Verification & reliability** | Self-verification, action gating, schema validation, retries. |
| **Supervisor + subagents** | Decompose goals and delegate to isolated subagents. |
| **Durable execution** | Run the loop as a Temporal workflow; steps become activities. |

## Design principles

- **Provider-agnostic core.** All LLM specifics live behind an `LlmClient` SPI;
  `agentkit-core` depends on no vendor SDK and is fully unit-testable with fakes.
- **Everything is progressive.** Tools, skills, and context disclose in tiers.
- **Verify before trusting.** Reliability is a first-class subsystem.
- **Durability is an integration.** The same loop runs in-process or on Temporal;
  the core never imports Temporal.

## Modules

```
agentkit-core/          # provider-agnostic core
agentkit-llm-anthropic/ # LlmClient over the Anthropic Java SDK (claude-opus-4-8)
agentkit-temporal/      # agent loop as a Temporal workflow
agentkit-examples/      # runnable end-to-end demos
```

Modules are introduced by the phase that first needs them.

## Requirements

- Java 21+
- Maven 3.9+

## Build

```bash
mvn verify
```

## License

See [LICENSE](LICENSE).
