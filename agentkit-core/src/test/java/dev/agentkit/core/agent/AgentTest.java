package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("test-model").maxSteps(5).build();

    private static Agent agent(LlmClient llm, Tool... tools) {
        return new Agent(llm, new SimpleToolRegistry(java.util.List.of(tools)), CONFIG);
    }

    private static dev.agentkit.core.message.Message lastMessage(dev.agentkit.core.llm.LlmRequest request) {
        var messages = request.messages();
        return messages.get(messages.size() - 1);
    }

    @Test
    void immediateTextResponseCompletes() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));

        AgentResult result = agent(llm).run(Goal.of("say done"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("done");
        assertThat(result.steps()).isEqualTo(1);
    }

    @Test
    void executesToolThenCompletes() {
        AtomicInteger called = new AtomicInteger();
        Tool adder = FunctionTool.builder("add", "adds a and b")
                .handler(inv -> {
                    called.incrementAndGet();
                    int a = ((Number) inv.argument("a")).intValue();
                    int b = ((Number) inv.argument("b")).intValue();
                    return ToolResult.ok(Integer.toString(a + b));
                })
                .build();

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "add", Map.of("a", 2, "b", 3)),
                FakeLlmClient.text("The answer is 5"));

        AgentResult result = agent(llm, adder).run(Goal.of("add 2 and 3"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("The answer is 5");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(called).hasValue(1);

        // The second request must carry the tool result back to the model.
        var toolResultMsg = lastMessage(llm.received().get(1));
        assertThat(toolResultMsg.role()).isEqualTo(Role.USER);
        assertThat(toolResultMsg.content().get(0)).isInstanceOf(ToolResultBlock.class);
        assertThat(((ToolResultBlock) toolResultMsg.content().get(0)).content()).isEqualTo("5");
    }

    @Test
    void unknownToolProducesErrorResultButContinues() {
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "does_not_exist", Map.of()),
                FakeLlmClient.text("recovered"));

        AgentResult result = agent(llm).run(Goal.of("try a missing tool"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("recovered");
        var toolResultMsg = lastMessage(llm.received().get(1));
        assertThat(((ToolResultBlock) toolResultMsg.content().get(0)).isError()).isTrue();
    }

    @Test
    void throwingToolBecomesErrorResult() {
        Tool boom = FunctionTool.builder("boom", "always fails")
                .handler(inv -> {
                    throw new IllegalStateException("kaboom");
                })
                .build();

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "boom", Map.of()),
                FakeLlmClient.text("handled the failure"));

        AgentResult result = agent(llm, boom).run(Goal.of("call boom"));

        assertThat(result.isSuccess()).isTrue();
        var toolResultMsg = lastMessage(llm.received().get(1));
        ToolResultBlock block = (ToolResultBlock) toolResultMsg.content().get(0);
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).contains("kaboom");
    }

    @Test
    void refusalStopsWithRefusedReason() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.refusal("I can't help with that"));

        AgentResult result = agent(llm).run(Goal.of("do something disallowed"));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void maxTokensMapsToOutputTruncated() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.maxTokens("truncated..."));

        AgentResult result = agent(llm).run(Goal.of("write a very long essay"));

        // A per-turn truncation, distinct from a run-wide BUDGET_EXHAUSTED stop.
        assertThat(result.stopReason()).isEqualTo(StopReason.OUTPUT_TRUNCATED);
        assertThat(result.output()).isEqualTo("truncated...");
    }

    @Test
    void loopStopsAtMaxSteps() {
        Tool loopTool = FunctionTool.builder("noop", "does nothing")
                .handler(inv -> ToolResult.ok("ok"))
                .build();
        // Always asks for a tool, so the loop never naturally ends.
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "noop", Map.of()),
                FakeLlmClient.toolUse("t2", "noop", Map.of()),
                FakeLlmClient.toolUse("t3", "noop", Map.of()));

        Agent agent = new Agent(llm, new SimpleToolRegistry(java.util.List.of(loopTool)),
                AgentConfig.builder("test-model").maxSteps(2).build());
        AgentResult result = agent.run(Goal.of("loop forever"));

        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_STEPS);
        assertThat(result.steps()).isEqualTo(2);
    }

    @Test
    void pauseStopsWithPausedReason() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.pause("waiting on server tool"));

        AgentResult result = agent(llm).run(Goal.of("kick off a long tool"));

        assertThat(result.stopReason()).isEqualTo(StopReason.PAUSED);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void tokenUsageAccumulatesAcrossSteps() {
        Tool noop = FunctionTool.builder("noop", "no-op").handler(inv -> ToolResult.ok("ok")).build();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUseWithUsage("t1", "noop", Map.of(),
                        new dev.agentkit.core.llm.TokenUsage(10, 5)),
                FakeLlmClient.textWithUsage("done", new dev.agentkit.core.llm.TokenUsage(2, 6)));

        AgentResult result = agent(llm, noop).run(Goal.of("do it"));

        // 10+2 input, 5+6 output across the two turns
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }

    @Test
    void budgetExhaustionStopsWithBudgetExhaustedNotError() {
        Tool noop = FunctionTool.builder("noop", "no-op").handler(inv -> ToolResult.ok("ok")).build();
        // Two turns are scripted, but a 40-token cap only lets the first run: it
        // spends 50 (0 < 40 at the guard), pushing the total to 50, and the second
        // turn is refused (50 >= 40) before it reaches the model.
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUseWithUsage("t1", "noop", Map.of(),
                        new dev.agentkit.core.llm.TokenUsage(30, 20)),
                FakeLlmClient.textWithUsage("should not run", new dev.agentkit.core.llm.TokenUsage(1, 1)));
        var budgeted = new dev.agentkit.core.reliability.BudgetLlmClient(
                llm, dev.agentkit.core.reliability.TokenBudget.ofTotalTokens(40));

        AgentResult result = new Agent(budgeted, new SimpleToolRegistry(java.util.List.of(noop)), CONFIG)
                .run(Goal.of("keep going"));

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(result.error()).isEmpty();
        assertThat(result.steps()).isEqualTo(1); // only the first turn completed
        assertThat(result.usage()).isEqualTo(new dev.agentkit.core.llm.TokenUsage(30, 20));
        assertThat(llm.received()).hasSize(1); // the second turn never reached the client
    }

    @Test
    void llmFailureBecomesFailedResult() {
        LlmClient failing = request -> {
            throw new LlmException("provider down");
        };

        AgentResult result = agent(failing).run(Goal.of("anything"));

        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(result.error()).isPresent();
        assertThat(result.steps()).isZero();
    }

    @Test
    void goalParametersAreRenderedIntoFirstMessage() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("ok"));

        agent(llm).run(new Goal("process order", Map.of("orderId", "A-100")));

        String firstUserMessage = llm.received().get(0).messages().get(0).text();
        assertThat(firstUserMessage).contains("process order").contains("orderId").contains("A-100");
    }
}
