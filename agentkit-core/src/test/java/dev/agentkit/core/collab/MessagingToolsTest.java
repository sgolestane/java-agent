package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MessagingToolsTest {

    private static Agent agentReplying(String reply) {
        return new Agent(new FakeLlmClient(FakeLlmClient.text(reply)),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    private static Agent agentRefusing() {
        return new Agent(new FakeLlmClient(FakeLlmClient.refusal("cannot help")),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    private static ToolResult send(Tool tool, String to, String message) {
        return tool.execute(new ToolInvocation("i", "send_message",
                Map.of("to", to, "message", message)));
    }

    @Test
    void runsThePeerAndReturnsItsReply() {
        SubagentRoster peers = SubagentRoster.of(
                Subagent.of("expert", "Answers questions", () -> agentReplying("the answer is 42")));
        Tool tool = MessagingTools.sendMessageTool(peers, MessagingTools.budget(5));

        ToolResult result = send(tool, "expert", "what is the answer?");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("the answer is 42");
    }

    @Test
    void sharedBudgetCapsTotalMessages() {
        SubagentRoster peers = SubagentRoster.of(
                Subagent.of("expert", "Answers", () -> agentReplying("ok")));
        AtomicInteger budget = MessagingTools.budget(1);
        Tool tool = MessagingTools.sendMessageTool(peers, budget);

        assertThat(send(tool, "expert", "first").isError()).isFalse();

        ToolResult second = send(tool, "expert", "second");
        assertThat(second.isError()).isTrue();
        assertThat(second.content()).contains("budget exhausted");
        assertThat(budget.get()).isZero(); // the rejected send did not consume the (already-zero) budget
    }

    @Test
    void unknownPeerIsAnError() {
        SubagentRoster peers = SubagentRoster.of(
                Subagent.of("expert", "Answers", () -> agentReplying("ok")));
        Tool tool = MessagingTools.sendMessageTool(peers, MessagingTools.budget(5));

        ToolResult result = send(tool, "nobody", "hi");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown agent 'nobody'");
    }

    @Test
    void missingArgumentsAreErrors() {
        SubagentRoster peers = SubagentRoster.of(
                Subagent.of("expert", "Answers", () -> agentReplying("ok")));
        Tool tool = MessagingTools.sendMessageTool(peers, MessagingTools.budget(5));

        assertThat(tool.execute(new ToolInvocation("i", "send_message", Map.of("message", "hi"))).isError())
                .isTrue();
        assertThat(tool.execute(new ToolInvocation("i", "send_message", Map.of("to", "expert"))).isError())
                .isTrue();
    }

    @Test
    void aPeerThatDoesNotCompleteComesBackAsAnError() {
        SubagentRoster peers = SubagentRoster.of(
                Subagent.of("flaky", "Refuses", MessagingToolsTest::agentRefusing));
        Tool tool = MessagingTools.sendMessageTool(peers, MessagingTools.budget(5));

        ToolResult result = send(tool, "flaky", "help");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("did not complete");
    }
}
