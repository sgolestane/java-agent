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

## Quick start

Wire an agent from a model client, a tool registry, and a config. Tools can be
progressively disclosed so the model only sees what it needs:

```java
// 1. A model client (Anthropic adapter, or any LlmClient / fake for tests).
LlmClient llm = AnthropicLlmClient.fromEnv(); // reads ANTHROPIC_API_KEY

// 2. Tools — a few always available, the long tail deferred behind search.
DisclosingToolRegistry tools = DisclosingToolRegistry.builder()
        .alwaysAvailable(FunctionTool.builder("finish", "Return the final answer")
                .handler(inv -> ToolResult.ok(inv.stringArgument("answer")))
                .schema(Map.of("type", "object",
                        "properties", Map.of("answer", Map.of("type", "string")),
                        "required", List.of("answer")))
                .build())
        .deferred(FunctionTool.builder("get_weather", "Get the weather for a city")
                .handler(inv -> ToolResult.ok("Sunny in " + inv.stringArgument("city")))
                .schema(Map.of("type", "object",
                        "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("city")))
                .build())
        .build();

// 3. Run the loop toward a goal.
Agent agent = new Agent(llm, tools,
        AgentConfig.builder(AnthropicLlmClient.DEFAULT_MODEL)
                .systemPrompt("You are a helpful assistant.")
                .maxSteps(10)
                .build());

AgentResult result = agent.run(Goal.of("What's the weather in Seattle?"));
System.out.println(result.output());
```

The agent starts seeing only `finish` and a `search_tools` tool; when it searches
for "weather" the `get_weather` tool is revealed and becomes callable.

### Skills

Skills add progressively-disclosed expertise. Load a directory of `SKILL.md`
skills and wire both halves (catalog + tools) in one step:

```java
SkillLibrary library = new SkillLibrary(SkillLoader.loadDirectory(Path.of("skills")));

// Register read_skill / read_skill_resource, and fold the catalog into the prompt.
SimpleToolRegistry tools = Skills.registerInto(new SimpleToolRegistry(), library);
String systemPrompt = Skills.systemPrompt("You are a helpful assistant.", library);

Agent agent = new Agent(llm, tools,
        AgentConfig.builder(AnthropicLlmClient.DEFAULT_MODEL).systemPrompt(systemPrompt).build());
```

Only each skill's name + description sit in context; the model calls `read_skill`
to load full instructions and `read_skill_resource` for bundled files on demand.

### Knowledge base

Ground the agent in your own data. Ingest documents and expose a search tool;
the mechanism (chunking, BM25/vector retrieval) is provided, the data and any
embedding model are yours:

```java
KnowledgeBase kb = InMemoryKnowledgeBase.bm25();          // or .vector(myEmbeddingModel)
kb.ingest(Document.of("refund-policy", "Refunds within 30 days with a receipt..."));

SimpleToolRegistry tools = new SimpleToolRegistry()
        .register(KnowledgeTools.knowledgeSearchTool(kb));
```

The agent calls `knowledge_search` and receives the most relevant passages with
their source ids.

### Memory

Give the agent memory that survives restarts. A durable `MemoryStore` (file-backed
or in-memory) is exposed as a `memory` tool; a per-run `WorkingMemory` scratchpad
is exposed as `remember`/`recall`:

```java
MemoryStore store = new FileMemoryStore(Path.of("agent-memory"));  // survives across runs
WorkingMemory scratch = new WorkingMemory();                       // this run only

SimpleToolRegistry tools = new SimpleToolRegistry()
        .register(MemoryTools.memoryTool(store))
        .register(MemoryTools.rememberTool(scratch))
        .register(MemoryTools.recallTool(scratch));
```

Across sessions the agent reads and writes durable facts under keys like
`facts/user.md`; paths are confined to the memory root (no traversal).

### Context engineering

Keep long-horizon runs within the context window. A `ContextStrategy` transforms
the history before every model turn — editing (prune old tool results) and
compaction (summarise older turns) compose behind one hook:

```java
ContextStrategy context = ContextStrategies.of(
        new ClearToolResultsEditor(6),                      // clear tool results older than 6 msgs
        SummarizingCompactor.builder(llm, model)            // summarise once history gets large
                .triggerTokens(120_000).keepRecentMessages(8).build());

Agent agent = new Agent(llm, tools, config, AgentObserver.NONE, context);
```

Editing runs first (cheap bulk removal), then compaction if still large; the
compaction boundary never orphans a tool result, and a failed summary falls back
to the full history.

## Requirements

- Java 21+
- Maven 3.9+

## Build

```bash
mvn verify
```

## License

See [LICENSE](LICENSE).
