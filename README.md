# AgentKit

A Java framework for building **reliable, unsupervised, tool-using AI agents**.

AgentKit gives a goal-driven agent the machinery it needs to run on its own:
progressive disclosure of tools and skills, a pluggable knowledge base, working
and long-term memory, deliberate context engineering, verification of its own
actions, and supervisor/subagent orchestration — with an optional **Temporal**
integration for durable execution.

> Status: **0.1 — all planned subsystems complete** and tested (`0.1.0-SNAPSHOT`).
> See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the pieces fit,
> [`docs/PLAN.md`](docs/PLAN.md) for the phased roadmap, and
> [`docs/RESEARCH.md`](docs/RESEARCH.md) for the design rationale. Runnable,
> fully-wired demos live in [`agentkit-examples`](agentkit-examples).

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
agentkit-llm-bedrock/   # the Anthropic adapter on Claude via Amazon Bedrock
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

### Verification & reliability

Don't trust the first answer, gate risky actions, and survive transient failures:

```java
// Retry transient LLM failures with backoff.
LlmClient reliable = new RetryingLlmClient(AnthropicLlmClient.fromEnv(), RetryPolicy.defaults());

// Gate hard-to-reverse tools (denied calls come back to the model as errors).
Agent agent = Agent.builder(reliable, tools, config)
        .toolGate(ToolGates.denyTools(Set.of("delete_account", "send_wire")))
        .build();

// Verify the outcome against the goal and retry with feedback if it fails.
SelfVerifyingAgent verified = new SelfVerifyingAgent(
        agent, new LlmVerifier(reliable, model), /* maxAttempts */ 3);

AgentResult result = verified.run(Goal.of("Produce a reconciled Q3 report"));
// result.stopReason() == VERIFICATION_FAILED if it never passed the critic.
```

Not every check needs a model. `Verifiers` supplies deterministic checks —
`matching(pattern)`, `containing(text)`, `satisfies(predicate, reason)` — and
`Verifiers.allOf(...)` composes a cheap structural check ahead of the LLM critic
so a run is gated on both, short-circuiting before spending a call.

### Supervisor & subagents

Decompose a goal across specialised subagents and synthesize their results. Each
subagent is a named `Agent` (built fresh per delegation, so parallel fan-out
shares no state):

```java
SubagentRoster roster = SubagentRoster.of(
        Subagent.of("researcher", "Finds and summarises facts",
                () -> new Agent(llm, researchTools, config)),
        Subagent.of("writer", "Turns notes into polished prose",
                () -> new Agent(llm, writerTools, config)));

// Programmatic fan-out: independent subgoals run concurrently, then synthesize.
Supervisor supervisor = Supervisor.builder(roster)
        .synthesizer(Synthesizers.llm(llm, model))   // or .concatenating()
        .build();

SupervisionResult result = supervisor.fanOut(Goal.of("Brief me on X"), List.of(
        DelegatedTask.of("researcher", "Gather the key facts about X"),
        DelegatedTask.of("writer", "Draft a one-paragraph brief")));
```

Fan-out is bounded: `maxConcurrency(n)` caps simultaneous subagents (so a large
decomposition doesn't flood a rate-limited backend) and `timeout(duration)` sets
an overall deadline — a subagent still running when it elapses is cancelled and
recorded as a failed outcome rather than stranding the rest.

When the split depends on intermediate results, let a supervisor *model* decide
instead: wire `SubagentTools.delegateTool(roster)` into an ordinary `Agent` and
it calls `delegate(subagent, goal)` one subgoal at a time.

### Durable execution (Temporal)

Run the *same* loop durably: every model turn and tool call becomes a Temporal
activity, so a run survives worker crashes and replays deterministically from
history without re-executing completed steps. The `agentkit-core` loop never
imports Temporal — durability is an integration in `agentkit-temporal`.

```java
// Share one data converter across the client and the worker.
WorkflowClientOptions opts = WorkflowClientOptions.newBuilder()
        .setDataConverter(TemporalAgent.dataConverter()).build();
WorkflowClient client = WorkflowClient.newInstance(service, opts);

// The worker's activities hold the real, side-effecting collaborators.
Worker worker = WorkerFactory.newInstance(client).newWorker("agentkit");
TemporalAgent.register(worker, llmClient, toolRegistry);
factory.start();

// Start a durable run. maxSteps bounds the loop; activity retries are durable.
AgentRunResult result = TemporalAgent.newStub(client, "agentkit")
        .run(DurableAgentRun.of(Goal.of("Reconcile Q3"), config, toolRegistry.advertisedSpecs()));
```

Model inference and tool execution run as activities with their own retry
policies; the conversation, step counter, and control flow live in the workflow
so Temporal replays them exactly. An LLM activity that exhausts its retries yields
a populated `ERROR` result (parity with in-process), not a failed workflow.

v1 durable notes: the tool set is fixed for the run (progressive disclosure would
need the revealed set tracked as durable state); context compaction issues its own
model call, so it belongs in a future activity; and because activity retry is
at-least-once, a non-idempotent tool should set `toolMaxAttempts = 1`.

### Running on Amazon Bedrock

The Anthropic adapter is backend-agnostic — `agentkit-llm-bedrock` runs the same
loop against Claude on Bedrock. Bedrock model ids carry an `anthropic.` prefix
(see `BedrockModels`); a `ModelResolver` maps the logical ids your agents use onto
the concrete wire ids, so nothing else in AgentKit changes.

There are **two invocation backends**, and the one you pick decides whether
application inference profiles apply:

| | `Bedrock.llmClient()` — Mantle | `Bedrock.invokeModel()` — InvokeModel |
|---|---|---|
| AWS surface | `bedrock-mantle` | `bedrock-runtime:InvokeModel` |
| IAM action | `bedrock-mantle:CreateInference` on a *project* | `bedrock:InvokeModel` on the model/profile |
| Cost attribution | per Bedrock project | per **application inference profile** |
| Application inference profiles | not applicable | **this is where they apply** |

If you use **application inference profiles** (account-specific ARNs for cost
attribution), you need the InvokeModel backend — profiles have no effect on the
Mantle path.

```java
// Mantle (recommended for new integrations, no inference profiles):
LlmClient llm = Bedrock.llmClient();            // AWS_REGION + default credential chain
// AgentConfig.builder(BedrockModels.CLAUDE_OPUS_4_8)...     // "anthropic.claude-opus-4-8"

// InvokeModel, no profiles — invoke a cross-region inference-profile id directly.
// (The bare "anthropic.claude-opus-4-8" is NOT on-demand invokable — use the geo form.)
LlmClient direct = Bedrock.invokeModel();
// AgentConfig.builder(BedrockModels.US_CLAUDE_OPUS_4_8)...  // "us.anthropic.claude-opus-4-8"

// InvokeModel + application inference profiles — discover ARNs, resolve logical ids to them:
try (BedrockClient control = BedrockClient.create()) {
    ModelResolver resolver = InferenceProfiles.resolver(control);  // lists your app profiles
    LlmClient llm2 = Bedrock.invokeModel(resolver);
    // AgentConfig model is the bare "anthropic.claude-opus-4-8"; each call is rewritten to your ARN.
}
```

If you obtain ARNs another way (config, SSM, your own discovery), skip discovery
and pass `ModelResolver.ofMap(yourMap)`.

In a **shared account** many application profiles can wrap the same model (e.g.
one per engineer). Discovery keys by model id, so those collide and it keeps the
first — which may not be one you can invoke. Pass a filter to narrow the roster
to yours: `InferenceProfiles.resolver(control, s -> s.inferenceProfileName() != null && s.inferenceProfileName().startsWith("eng-me-"))`.

**Run a demo on Bedrock.** The example `main`s honour `AGENTKIT_BACKEND=bedrock`
and resolve AWS credentials/region through the standard chain — including a named
**SSO** profile. By default the demo uses the Mantle backend; set
`AGENTKIT_BEDROCK_INVOKE_MODEL=true` for the InvokeModel backend, or
`AGENTKIT_BEDROCK_DISCOVER_PROFILES=true` to additionally discover your
application inference profiles (which implies InvokeModel):

```bash
aws sso login --profile thira-eng-bedrock          # ensure a valid session
export AWS_PROFILE=thira-eng-bedrock
export AWS_REGION=us-west-2                         # the region your profiles live in
export AGENTKIT_BACKEND=bedrock
export AGENTKIT_BEDROCK_DISCOVER_PROFILES=true      # InvokeModel + map logical ids → your profile ARNs
export AGENTKIT_BEDROCK_PROFILE_PREFIX=eng-me-      # in a shared account, keep only your profiles

mvn install -DskipTests                            # once — publish the modules locally
mvn -f agentkit-examples/pom.xml exec:exec \        # fork a JVM to run the demo main()
    -Dexec.mainClass=dev.agentkit.examples.EndToEndAgent
```

If you already know your profile ARN, skip discovery entirely and invoke it
directly: `export AGENTKIT_BEDROCK_MODEL=arn:aws:bedrock:us-west-2:123:application-inference-profile/abc`.

The InvokeModel path needs `bedrock:InvokeModel` on the model (or on your
application-inference-profile ARN), plus `bedrock:ListInferenceProfiles` when
`AGENTKIT_BEDROCK_DISCOVER_PROFILES=true`. The Mantle default instead needs
`bedrock-mantle:CreateInference` on the project.

Two things matter in that command: run *inside* the module (`-f
agentkit-examples/pom.xml`, or `cd agentkit-examples` first) so the goal doesn't
run against the aggregator root, and use **`exec:exec`** (forks a JVM), not
`exec:java` — the in-process runner's classloader mishandles the AWS SDK and fails
with a spurious *"the 'sso' service module must be on the class path"* even though
the jars are present. The one-time `mvn install` lets the module resolve its
sibling jars from your local repository.

The `agentkit-examples` module bundles the AWS `sso`/`ssooidc` modules so an SSO
profile resolves out of the box; a library that uses SSO must add those two AWS
SDK artifacts itself. No `ANTHROPIC_API_KEY` is needed on the Bedrock path.

### Web research (a search tool)

`WebResearchAgent` gives an agent a single `web_search` tool and asks it a
question worth grounding in current docs — *how to add a user to a group with the
Microsoft Graph API*. The agent searches, then answers from the results and cites
the URLs.

`web_search` is a **client-executed** tool (`WebResearchTools.webSearchTool`) over
a small `WebSearch` seam — so it works against any backend, **including Bedrock**,
where Anthropic's server-side web-search tool isn't available. The *search tool* needs no
setup — it returns offline sample results by default; set `TAVILY_API_KEY` to
search the live web via `TavilyWebSearch` (JDK HTTP client + Jackson, no vendor
SDK). Swap in another provider by implementing `WebSearch`. The *model* still
comes from `ExampleBackend`, so set `ANTHROPIC_API_KEY` (or the Bedrock vars
above) as for any other demo.

```bash
export ANTHROPIC_API_KEY=sk-ant-...                # model backend (or the Bedrock vars above)
export TAVILY_API_KEY=tvly-...                     # optional — omit for offline sample search results

mvn install -DskipTests                            # once — publish the modules locally
mvn -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.WebResearchAgent
```

### Agent collaboration

Beyond the top-down supervisor/subagent delegation (`Supervisor.fanOut` and the
`delegate` tool), the `dev.agentkit.core.collab` package lets agents work *with*
each other as peers, in three composable ways:

- **Shared workspace** — a concurrency-safe `Blackboard` several agents post to
  and read from (`BlackboardTools.postNoteTool` / `readBoardTool`), so they build
  on each other's partial work instead of each starting fresh. Each agent's
  `post_note` tool binds its author, so posts are reliably attributed.
- **Agent-to-agent messaging** — `MessagingTools.sendMessageTool` gives any agent
  a `send_message(to, message)` tool: it runs a named peer to completion and
  returns the reply, so peers hold a real request/response conversation (not just
  one-way dispatch). A shared message `budget(N)` bounds the whole exchange and
  guarantees termination.
- **Generator↔critic refine loop** — `RefineLoop` has one agent draft, a peer
  `Critic` review, and the generator revise against the feedback until the critic
  approves or a round cap is hit. The critic can be a single model call
  (`Critics.llm`) or a whole peer agent (`Critics.agent`).

`CollaborationExample` wires all three: a writer messages a `researcher` peer for
facts, jots them to the shared board, and is refined by an `editor` critic.

```bash
mvn -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.CollaborationExample
```

## Requirements

- Java 21+
- Maven 3.9+

## Build

```bash
mvn verify
```

## License

See [LICENSE](LICENSE).
