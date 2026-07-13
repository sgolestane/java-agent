package dev.agentkit.core.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VerifyTest {

    private static Agent textAgent(FakeLlmClient llm) {
        return new Agent(llm, new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    // --- LlmVerifier --------------------------------------------------------

    @Test
    void llmVerifierParsesPass() {
        Verdict v = new LlmVerifier(new FakeLlmClient(FakeLlmClient.text("PASS")), "m")
                .verify(Goal.of("g"), "output");
        assertThat(v.passed()).isTrue();
    }

    @Test
    void llmVerifierParsesFailWithFeedback() {
        Verdict v = new LlmVerifier(new FakeLlmClient(FakeLlmClient.text("FAIL\nMissing the total row.")), "m")
                .verify(Goal.of("g"), "output");
        assertThat(v.passed()).isFalse();
        assertThat(v.feedback()).isEqualTo("Missing the total row.");
    }

    @Test
    void llmVerifierFailsClosedOnGarbage() {
        Verdict v = new LlmVerifier(new FakeLlmClient(FakeLlmClient.text("maybe?")), "m")
                .verify(Goal.of("g"), "output");
        assertThat(v.passed()).isFalse();
    }

    // --- SelfVerifyingAgent -------------------------------------------------

    @Test
    void returnsImmediatelyWhenVerificationPasses() {
        AgentResult r = new SelfVerifyingAgent(
                textAgent(new FakeLlmClient(FakeLlmClient.text("answer"))),
                Verifier.ALWAYS_PASS, 3).run(Goal.of("do it"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.output()).isEqualTo("answer");
    }

    @Test
    void retriesWithFeedbackThenPasses() {
        // Agent produces answer1 then answer2 across two runs.
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("answer1"), FakeLlmClient.text("answer2"));
        AtomicInteger checks = new AtomicInteger();
        Verifier flaky = (goal, output) -> checks.getAndIncrement() == 0
                ? Verdict.fail("try harder") : Verdict.pass();

        AgentResult r = new SelfVerifyingAgent(textAgent(llm), flaky, 3).run(Goal.of("do it"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.output()).isEqualTo("answer2");
        assertThat(checks).hasValue(2);
        // The retry goal carried the verifier feedback.
        assertThat(llm.received().get(1).messages().get(0).text()).contains("try harder");
    }

    @Test
    void exhaustingAttemptsYieldsVerificationFailed() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("a1"), FakeLlmClient.text("a2"));
        AgentResult r = new SelfVerifyingAgent(textAgent(llm), (g, o) -> Verdict.fail("nope"), 2)
                .run(Goal.of("do it"));

        assertThat(r.stopReason()).isEqualTo(StopReason.VERIFICATION_FAILED);
        assertThat(r.output()).isEqualTo("a2");
    }

    @Test
    void agentFailureIsReturnedWithoutVerifying() {
        AtomicInteger checks = new AtomicInteger();
        AgentResult r = new SelfVerifyingAgent(
                textAgent(new FakeLlmClient(FakeLlmClient.refusal("no"))),
                (g, o) -> {
                    checks.incrementAndGet();
                    return Verdict.pass();
                }, 3).run(Goal.of("do it"));

        assertThat(r.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(checks).hasValue(0); // verifier never consulted
    }
}
