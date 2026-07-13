package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ToolGatesTest {

    private static ToolInvocation inv(String name) {
        return new ToolInvocation("i", name, Map.of());
    }

    @Test
    void denyToolsBlocksNamedTools() {
        ToolGate gate = ToolGates.denyTools(Set.of("delete_all"));
        assertThat(gate.evaluate(inv("delete_all")).allowed()).isFalse();
        assertThat(gate.evaluate(inv("read")).allowed()).isTrue();
    }

    @Test
    void requireConfirmationDeniesWhenHandlerRejects() {
        ToolGate gate = ToolGates.requireConfirmation(i -> i.name().equals("send"), ConfirmationHandler.DENY_ALL);
        assertThat(gate.evaluate(inv("send")).allowed()).isFalse();
        assertThat(gate.evaluate(inv("read")).allowed()).isTrue();
    }

    @Test
    void requireConfirmationAllowsWhenApproved() {
        ToolGate gate = ToolGates.requireConfirmation(i -> true, ConfirmationHandler.ALLOW_ALL);
        assertThat(gate.evaluate(inv("send")).allowed()).isTrue();
    }

    @Test
    void allOfDeniesIfAnyDenies() {
        ToolGate gate = ToolGates.allOf(
                ToolGates.allowAll(),
                ToolGates.denyIf(i -> i.name().equals("x"), "blocked x"));
        assertThat(gate.evaluate(inv("x")).allowed()).isFalse();
        assertThat(gate.evaluate(inv("x")).reason()).isEqualTo("blocked x");
        assertThat(gate.evaluate(inv("y")).allowed()).isTrue();
    }

    @Test
    void agentSurfacesGateDenialAsErrorResultAndContinues() {
        AtomicBoolean executed = new AtomicBoolean(false);
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("send_email", "sends an email")
                        .handler(i -> {
                            executed.set(true);
                            return ToolResult.ok("sent");
                        }).build());

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "send_email", Map.of("to", "x")),
                FakeLlmClient.text("I could not send it."));

        Agent agent = Agent.builder(llm, registry, AgentConfig.builder("m").maxSteps(5).build())
                .toolGate(ToolGates.denyTools(Set.of("send_email")))
                .build();
        AgentResult result = agent.run(Goal.of("email someone"));

        assertThat(executed).isFalse(); // gate blocked execution
        assertThat(result.isSuccess()).isTrue(); // run continued after the denial
        var toolMsg = llm.received().get(1).messages().get(2);
        assertThat(((ToolResultBlock) toolMsg.content().get(0)).isError()).isTrue();
    }
}
