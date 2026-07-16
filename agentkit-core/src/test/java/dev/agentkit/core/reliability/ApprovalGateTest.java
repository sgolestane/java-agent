package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ApprovalGateTest {

    private static ToolInvocation inv(String name, Map<String, Object> args) {
        return new ToolInvocation("i", name, args);
    }

    @Test
    void nonMatchingInvocationsAreAllowedUnchanged() {
        ToolGate gate = ToolGates.requireApproval(i -> i.name().equals("send"), Approver.DENY_ALL);
        GateResult result = gate.evaluate(inv("read", Map.of()));
        assertThat(result.allowed()).isTrue();
        assertThat(result.replacement()).isEmpty();
    }

    @Test
    void approveAllAllowsMatchingInvocations() {
        ToolGate gate = ToolGates.requireApproval(i -> true, Approver.APPROVE_ALL);
        assertThat(gate.evaluate(inv("send", Map.of())).allowed()).isTrue();
    }

    @Test
    void denyAllSurfacesASafeDefaultReason() {
        ToolGate gate = ToolGates.requireApproval(i -> true, Approver.DENY_ALL);
        GateResult result = gate.evaluate(inv("send", Map.of()));
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("human approval");
    }

    @Test
    void customDenyReasonReachesTheGateResult() {
        Approver approver = i -> ApprovalDecision.deny("recipient not on the allowlist");
        ToolGate gate = ToolGates.requireApproval(i -> true, approver);
        assertThat(gate.evaluate(inv("send", Map.of())).reason()).isEqualTo("recipient not on the allowlist");
    }

    @Test
    void approveWithArgumentsReplacesTheInvocationPreservingIdAndName() {
        Approver approver = i -> ApprovalDecision.approveWithArguments(Map.of("amount", 10));
        ToolGate gate = ToolGates.requireApproval(i -> true, approver);

        GateResult result = gate.evaluate(inv("wire", Map.of("amount", 1_000_000)));

        assertThat(result.allowed()).isTrue();
        ToolInvocation replacement = result.replacement().orElseThrow();
        assertThat(replacement.id()).isEqualTo("i");
        assertThat(replacement.name()).isEqualTo("wire");
        assertThat(replacement.argument("amount")).isEqualTo(10);
    }

    @Test
    void argumentsAccessorRejectsNonEditDecisions() {
        assertThatThrownBy(() -> ApprovalDecision.approve().arguments())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApprovalDecision.deny("no").arguments())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeniedGateResultCannotCarryAReplacement() {
        assertThatThrownBy(() -> new GateResult(false, "no", Optional.of(inv("x", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editedArgumentsActuallyReachTheToolInTheAgentLoop() {
        AtomicReference<Object> executedAmount = new AtomicReference<>();
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("wire", "wires money")
                        .handler(i -> {
                            executedAmount.set(i.argument("amount"));
                            return ToolResult.ok("wired");
                        }).build());

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "wire", Map.of("amount", 1_000_000)),
                FakeLlmClient.text("Done."));

        // The approver caps the amount at 10 instead of the model's 1,000,000.
        Approver capping = i -> ApprovalDecision.approveWithArguments(Map.of("amount", 10));
        Agent agent = Agent.builder(llm, registry, AgentConfig.builder("m").maxSteps(5).build())
                .toolGate(ToolGates.requireApproval(i -> i.name().equals("wire"), capping))
                .build();

        AgentResult result = agent.run(Goal.of("wire a lot of money"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(executedAmount.get()).isEqualTo(10); // the edited value, not 1,000,000
    }
}
