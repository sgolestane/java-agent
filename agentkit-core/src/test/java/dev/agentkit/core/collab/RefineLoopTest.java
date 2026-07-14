package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RefineLoopTest {

    private static final Goal GOAL = Goal.of("Summarize durable execution.");

    private static Agent agentReturning(String text) {
        return new Agent(new FakeLlmClient(FakeLlmClient.text(text)),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    /** A generator that emits {@code drafts} in order, one per round. */
    private static Supplier<Agent> generatorEmitting(String... drafts) {
        int[] round = {0};
        return () -> agentReturning(drafts[Math.min(round[0]++, drafts.length - 1)]);
    }

    @Test
    void approvesOnTheFirstRound() {
        RefineLoop loop = new RefineLoop(generatorEmitting("draft v1"),
                (goal, draft) -> Critique.approve(), 3);

        RefineLoop.RefineResult result = loop.run(GOAL);

        assertThat(result.approved()).isTrue();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("draft v1");
        assertThat(result.lastFeedback()).isEmpty();
    }

    @Test
    void revisesOnceThenApproves() {
        int[] reviews = {0};
        RefineLoop loop = new RefineLoop(
                generatorEmitting("draft v1", "draft v2"),
                (goal, draft) -> reviews[0]++ == 0 ? Critique.revise("add detail") : Critique.approve(),
                4);

        RefineLoop.RefineResult result = loop.run(GOAL);

        assertThat(result.approved()).isTrue();
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.output()).isEqualTo("draft v2"); // the revised draft
    }

    @Test
    void stopsAtTheRoundCapWithTheBestDraftWhenNeverApproved() {
        RefineLoop loop = new RefineLoop(
                generatorEmitting("draft v1", "draft v2"),
                (goal, draft) -> Critique.revise("still not there"),
                2);

        RefineLoop.RefineResult result = loop.run(GOAL);

        assertThat(result.approved()).isFalse();
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.output()).isEqualTo("draft v2"); // last draft produced
        assertThat(result.lastFeedback()).isEqualTo("still not there");
    }

    @Test
    void stopsEarlyIfAGeneratorRoundDoesNotComplete() {
        Supplier<Agent> refusing = () -> new Agent(
                new FakeLlmClient(FakeLlmClient.refusal("cannot")),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
        int[] reviews = {0};
        RefineLoop loop = new RefineLoop(refusing,
                (goal, draft) -> {
                    reviews[0]++;
                    return Critique.approve();
                }, 3);

        RefineLoop.RefineResult result = loop.run(GOAL);

        assertThat(result.approved()).isFalse();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("cannot");
        assertThat(reviews[0]).isZero(); // the critic is never consulted on a failed round
    }

    @Test
    void rejectsANonPositiveRoundCap() {
        assertThatThrownBy(() -> new RefineLoop(generatorEmitting("x"), (g, d) -> Critique.approve(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRounds");
    }
}
