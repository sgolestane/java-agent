package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentToolsTest {

    private static Subagent textSubagent(String name, String output) {
        return Subagent.of(name, name + " specialist", () -> new Agent(
                new FakeLlmClient(FakeLlmClient.text(output)),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
    }

    private static ToolResult call(Tool tool, Map<String, Object> args) {
        return tool.execute(new ToolInvocation("i", "delegate", args));
    }

    @Test
    void toolDescriptionListsSubagents() {
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(
                textSubagent("researcher", "x"), textSubagent("writer", "y")));
        assertThat(tool.description()).contains("researcher").contains("writer");
        assertThat(tool.inputSchema()).containsKey("properties");
    }

    @Test
    void delegateRunsNamedSubagentAndReturnsOutput() {
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(textSubagent("calc", "42")));
        ToolResult result = call(tool, Map.of("subagent", "calc", "goal", "what is 6*7"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("42");
    }

    @Test
    void delegateReportsUnknownSubagentAsError() {
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(textSubagent("calc", "42")));
        ToolResult result = call(tool, Map.of("subagent", "ghost", "goal", "x"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown subagent").contains("calc");
    }

    @Test
    void delegateReportsMissingArguments() {
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(textSubagent("calc", "42")));
        assertThat(call(tool, Map.of("goal", "x")).isError()).isTrue();
        assertThat(call(tool, Map.of("subagent", "calc")).isError()).isTrue();
    }

    @Test
    void delegateSurfacesSubagentFailure() {
        Subagent refuser = Subagent.of("refuser", "refuses", () -> new Agent(
                new FakeLlmClient(FakeLlmClient.refusal("cannot")),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(refuser));
        ToolResult result = call(tool, Map.of("subagent", "refuser", "goal", "x"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("REFUSED");
    }

    @Test
    void delegateReturnsErrorWhenSubagentFactoryThrows() {
        Subagent broken = Subagent.of("broken", "throws on build",
                () -> { throw new IllegalStateException("factory boom"); });
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(broken));
        ToolResult result = call(tool, Map.of("subagent", "broken", "goal", "x"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("broken").contains("failed");
    }

    @Test
    void delegateToolNameConstantMatches() {
        assertThat(SubagentTools.DELEGATE).isEqualTo("delegate");
        Tool tool = SubagentTools.delegateTool(SubagentRoster.of(textSubagent("a", "x")));
        assertThat(tool.name()).isEqualTo(SubagentTools.DELEGATE);
    }

    @Test
    void supervisorAgentDrivesDelegationThroughTheLoop() {
        // The supervisor model delegates to "specialist", then finishes with its output.
        SubagentRoster roster = SubagentRoster.of(textSubagent("specialist", "specialist answer"));
        var registry = new SimpleToolRegistry().register(SubagentTools.delegateTool(roster));

        FakeLlmClient supervisorLlm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "delegate",
                        Map.of("subagent", "specialist", "goal", "do the thing")),
                FakeLlmClient.text("Final: specialist answer"));

        Agent supervisor = new Agent(supervisorLlm, registry, AgentConfig.builder("m").maxSteps(5).build());
        AgentResult result = supervisor.run(Goal.of("coordinate"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("Final: specialist answer");
        // The supervisor's second turn saw the subagent's output as the tool result.
        var toolResultMsg = supervisorLlm.received().get(1).messages().get(2);
        var block = (dev.agentkit.core.message.ToolResultBlock) toolResultMsg.content().get(0);
        assertThat(block.content()).contains("specialist answer");
    }
}
