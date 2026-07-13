package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupervisorTest {

    /** A subagent that always produces {@code output} with the given usage. */
    private static Subagent textSubagent(String name, String output, TokenUsage usage) {
        return Subagent.of(name, name, () -> new Agent(
                new FakeLlmClient(FakeLlmClient.textWithUsage(output, usage)),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
    }

    private static Subagent textSubagent(String name, String output) {
        return textSubagent(name, output, TokenUsage.ZERO);
    }

    @Test
    void fanOutCollectsOutcomesInTaskOrderAndSynthesizes() {
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("weather", "It is sunny."),
                textSubagent("news", "Markets are up."));
        Supervisor supervisor = Supervisor.of(roster);

        SupervisionResult result = supervisor.fanOut(Goal.of("brief me"), List.of(
                DelegatedTask.of("weather", "weather?"),
                DelegatedTask.of("news", "news?")));

        assertThat(result.outcomes()).extracting(SubagentOutcome::subagent)
                .containsExactly("weather", "news");
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.output())
                .isEqualTo("## weather\nIt is sunny.\n\n## news\nMarkets are up.");
    }

    @Test
    void fanOutAggregatesStepsAndUsage() {
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("a", "one", new TokenUsage(10, 5)),
                textSubagent("b", "two", new TokenUsage(3, 2)));
        Supervisor supervisor = Supervisor.of(roster);

        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("a", "x"), DelegatedTask.of("b", "y")));

        assertThat(result.totalSteps()).isEqualTo(2); // one model turn each
        assertThat(result.totalUsage()).isEqualTo(new TokenUsage(13, 7));
    }

    @Test
    void aFailingSubagentDoesNotAbortTheOthers() {
        Subagent refuser = Subagent.of("refuser", "refuses", () -> new Agent(
                new FakeLlmClient(FakeLlmClient.refusal("no")),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
        SubagentRoster roster = SubagentRoster.of(textSubagent("ok", "done"), refuser);
        Supervisor supervisor = Supervisor.of(roster);

        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("ok", "x"), DelegatedTask.of("refuser", "y")));

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.failures()).extracting(SubagentOutcome::subagent).containsExactly("refuser");
        // The successful outcome is still present and synthesized.
        assertThat(result.output()).contains("done");
    }

    @Test
    void unknownSubagentIsRejectedBeforeRunning() {
        Supervisor supervisor = Supervisor.of(SubagentRoster.of(textSubagent("known", "x")));
        assertThatThrownBy(() -> supervisor.fanOut(Goal.of("g"),
                List.of(DelegatedTask.of("ghost", "y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void emptyTaskListSynthesizesEmpty() {
        Supervisor supervisor = Supervisor.of(SubagentRoster.of(textSubagent("a", "x")));
        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of());
        assertThat(result.outcomes()).isEmpty();
        assertThat(result.output()).isEmpty();
        assertThat(result.totalSteps()).isZero();
    }

    @Test
    void llmSynthesizerReconcilesOutputs() {
        FakeLlmClient synthLlm = new FakeLlmClient(FakeLlmClient.text("Combined brief."));
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        textSubagent("a", "part a"), textSubagent("b", "part b")))
                .synthesizer(Synthesizers.llm(synthLlm, "m"))
                .build();

        SupervisionResult result = supervisor.fanOut(Goal.of("brief"), List.of(
                DelegatedTask.of("a", "x"), DelegatedTask.of("b", "y")));

        assertThat(result.output()).isEqualTo("Combined brief.");
        // The synthesizer saw both parts and the original goal.
        String prompt = synthLlm.received().get(0).messages().get(0).text();
        assertThat(prompt).contains("part a").contains("part b").contains("brief");
    }
}
