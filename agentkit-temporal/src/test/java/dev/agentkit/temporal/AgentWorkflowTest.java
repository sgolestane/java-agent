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
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
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
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
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
        // 3 generate calls = turn1 (once) + turn2 (fail + success). Turn 1's completed
        // activity was NOT re-run when turn 2 was retried.
        assertThat(llm.callCount()).isEqualTo(3);
        // The tool's side effect happened exactly once despite the later retry.
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
