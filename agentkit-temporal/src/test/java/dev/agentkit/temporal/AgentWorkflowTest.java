package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ToolUseBlock;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the durable agent loop in Temporal's in-memory test environment (no
 * external server): a simple completion, a tool round-trip through the activity
 * boundary, step bounding, refusal, and — the durability payoff — replay after a
 * transient activity failure without re-executing completed activities.
 */
class AgentWorkflowTest {

    private static final String TASK_QUEUE = "agentkit-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private void start(LlmClient llm, ToolRegistry tools) {
        // Disable the sticky workflow cache so every workflow task replays from
        // history. This makes the memoization assertions meaningful: if a completed
        // activity were re-executed on replay, its invocation count would balloon.
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, llm, tools);
        env.start();
    }

    private AgentRunResult run(DurableAgentRun run) {
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE).run(run);
    }

    private static AgentConfig config(int maxSteps) {
        return AgentConfig.builder("m").maxSteps(maxSteps).build();
    }

    private static FunctionTool echoTool(AtomicInteger counter) {
        return FunctionTool.builder("echo", "Echoes its text argument")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .handler(inv -> {
                    counter.incrementAndGet();
                    return ToolResult.ok("echoed:" + inv.stringArgument("text"));
                })
                .build();
    }

    @Test
    void completesASimpleTextRun() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("the answer"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("do it"), config(3)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("the answer");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    void executesAToolThroughAnActivityThenCompletes() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "hi")))
                .then(ScriptedLlm.text("done"));
        start(llm, tools);

        List<ToolSpec> specs = tools.advertisedSpecs();
        AgentRunResult result = run(DurableAgentRun.of(Goal.of("use the tool"), config(5), specs));

        assertThat(result.output()).isEqualTo("done");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void replaysAfterTransientLlmFailureWithoutRerunningCompletedActivities() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        // Turn 1: tool_use (succeeds, memoized). Tool runs (succeeds, memoized).
        // Turn 2: the LLM activity fails once, then succeeds on Temporal's retry.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "hi")))
                .fail()
                .then(ScriptedLlm.text("done"));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("do it"), config(5), tools.advertisedSpecs()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("done");
        // With the sticky cache disabled (see start()), each workflow task replays
        // from history. 3 generate calls = turn1 (once) + turn2 (fail + retry); if the
        // completed turn-1 LLM activity were re-run on those replays the count would be
        // far higher. Likewise the tool ran exactly once despite the later retry+replay.
        assertThat(llm.callCount()).isEqualTo(3);
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void aggregatesTokenUsageAcrossTurns() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUseWithUsage("t1", "echo", Map.of("text", "hi"),
                        new TokenUsage(10, 5)))
                .then(ScriptedLlm.textWithUsage("done", new TokenUsage(3, 2)));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs()));

        assertThat(result.usage()).isEqualTo(new TokenUsage(13, 7));
    }

    @Test
    void maxTokensStopsWithBudgetExhausted() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.maxTokens("truncated..."));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(result.output()).isEqualTo("truncated...");
    }

    @Test
    void pauseStopsWithPaused() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.pause("waiting"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.PAUSED);
    }

    @Test
    void toolErrorResultFlowsBackAndTheLoopContinues() {
        AtomicInteger calls = new AtomicInteger();
        FunctionTool failing = FunctionTool.builder("boom", "always fails")
                .handler(inv -> {
                    calls.incrementAndGet();
                    return ToolResult.error("kaboom");
                })
                .build();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(failing);
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "boom", Map.of()))
                .then(ScriptedLlm.text("recovered"));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs()));

        assertThat(result.output()).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(1); // the failing tool ran, run continued
    }

    @Test
    void unknownToolBecomesAnErrorResultNotAFailure() {
        // The model asks for a tool that isn't registered; the activity returns an
        // error result the model reacts to, rather than crashing the run.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "ghost", Map.of()))
                .then(ScriptedLlm.text("handled"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(5)));

        assertThat(result.output()).isEqualTo("handled");
    }

    @Test
    void runsMultipleToolUsesInOneTurn() {
        AtomicInteger calls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(calls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.multiToolUse(List.of(
                        new ToolUseBlock("t1", "echo", Map.of("text", "a")),
                        new ToolUseBlock("t2", "echo", Map.of("text", "b")))))
                .then(ScriptedLlm.text("both done"));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs()));

        assertThat(result.output()).isEqualTo("both done");
        assertThat(calls.get()).isEqualTo(2); // both tool_use blocks executed
    }

    @Test
    void deliversGoalParametersSystemPromptAndOptionsToTheModel() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("ok"));
        start(llm, new SimpleToolRegistry());

        Goal goal = new Goal("summarize", Map.of("docId", "42"));
        var cfg = AgentConfig.builder("m").maxSteps(2)
                .systemPrompt("You are terse.").option("effort", "high").build();
        run(new DurableAgentRun(goal, cfg, List.of(), DurableAgentOptions.defaults()));

        var request = llm.requests().get(0);
        assertThat(request.system()).contains("You are terse.");
        assertThat(request.options()).containsEntry("effort", "high");
        // The rendered goal (description + parameters) reached the model.
        assertThat(request.messages().get(0).text()).contains("summarize").contains("docId: 42");
    }

    @Test
    void llmExhaustionYieldsAnErrorResultWithPartialProgress() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        // Turn 1 succeeds (a tool call); turn 2 fails every attempt.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "hi")))
                .fail().fail();
        start(llm, tools);

        DurableAgentOptions opts = new DurableAgentOptions(60, 2, 60, 3); // llmMaxAttempts=2
        AgentRunResult result = run(new DurableAgentRun(
                Goal.of("g"), config(5), tools.advertisedSpecs(), opts));

        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(result.errorMessage()).isNotEmpty();
        assertThat(result.steps()).isEqualTo(1); // the first successful turn counted
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void stopsAtMaxSteps() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "a")))
                .then(ScriptedLlm.toolUse("t2", "echo", Map.of("text", "b")));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("loop"), config(2), tools.advertisedSpecs()));

        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_STEPS);
        assertThat(result.steps()).isEqualTo(2);
        assertThat(toolCalls.get()).isEqualTo(2);
    }

    @Test
    void refusalStopsTheRun() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.refusal("cannot help"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("do it"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(result.output()).isEqualTo("cannot help");
    }
}
