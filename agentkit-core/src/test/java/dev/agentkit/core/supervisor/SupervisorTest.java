package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

        assertThat(result.outcomes()).extracting(SubagentOutcome::subagentName)
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
        assertThat(result.failures()).extracting(SubagentOutcome::subagentName).containsExactly("refuser");
        // The successful outcome is still present and synthesized, and the failure is
        // annotated with its stop reason in the concatenated output.
        assertThat(result.output()).contains("done").contains("## refuser (REFUSED)");
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

    @Test
    void llmSynthesizerFallsBackToConcatenationOnModelFailure() {
        // An empty FakeLlmClient throws LlmException on generate → fallback.
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        textSubagent("a", "part a"), textSubagent("b", "part b")))
                .synthesizer(Synthesizers.llm(new FakeLlmClient(), "m"))
                .build();

        SupervisionResult result = supervisor.fanOut(Goal.of("brief"), List.of(
                DelegatedTask.of("a", "x"), DelegatedTask.of("b", "y")));

        assertThat(result.output()).isEqualTo("## a\npart a\n\n## b\npart b");
    }

    @Test
    void aggregatedUsageExcludesTheSynthesisCall() {
        // Synthesis model call has non-zero usage; it must NOT be counted.
        FakeLlmClient synthLlm = new FakeLlmClient(
                FakeLlmClient.textWithUsage("done", new TokenUsage(100, 100)));
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        textSubagent("a", "x", new TokenUsage(4, 1))))
                .synthesizer(Synthesizers.llm(synthLlm, "m"))
                .build();

        SupervisionResult result = supervisor.fanOut(Goal.of("g"),
                List.of(DelegatedTask.of("a", "x")));

        assertThat(result.totalUsage()).isEqualTo(new TokenUsage(4, 1)); // subagent only
    }

    @Test
    @Timeout(10)
    void maxConcurrencyLimitsSimultaneousSubagents() {
        int limit = 2;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        // Each subagent records the peak concurrency it observes, then blocks until
        // released so overlap is forced if the limit is not honoured. The accounting
        // lives in the factory, which runs on the subagent's own thread.
        Subagent counting = Subagent.of("probe", "probe", () -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new Agent(new FakeLlmClient(FakeLlmClient.text("x")),
                    new SimpleToolRegistry(), AgentConfig.builder("m").build());
        });

        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(counting))
                .maxConcurrency(limit).build();

        // Release the probes shortly after fan-out begins on another thread.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();

        supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("probe", "1"), DelegatedTask.of("probe", "2"),
                DelegatedTask.of("probe", "3"), DelegatedTask.of("probe", "4")));

        assertThat(peak.get()).isLessThanOrEqualTo(limit);
    }

    @Test
    @Timeout(10)
    void timeoutMarksSlowSubagentAsFailedWithoutStrandingOthers() {
        CountDownLatch blocked = new CountDownLatch(1);
        Subagent slow = Subagent.of("slow", "slow", () -> {
            try {
                blocked.await(); // never released within the deadline
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Agent(new FakeLlmClient(FakeLlmClient.text("late")),
                    new SimpleToolRegistry(), AgentConfig.builder("m").build());
        });
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(textSubagent("fast", "quick"), slow))
                .timeout(Duration.ofMillis(200)).build();

        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("fast", "x"), DelegatedTask.of("slow", "y")));

        blocked.countDown(); // let the cancelled thread unwind
        assertThat(result.outcomes()).extracting(SubagentOutcome::subagentName)
                .containsExactly("fast", "slow");
        assertThat(result.outcomes().get(0).succeeded()).isTrue();  // fast one not stranded
        assertThat(result.outcomes().get(1).succeeded()).isFalse(); // slow one timed out
    }
}
