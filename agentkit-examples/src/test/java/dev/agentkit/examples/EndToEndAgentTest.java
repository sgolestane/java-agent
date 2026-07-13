package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.verify.SelfVerifyingAgent;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives the fully-wired {@link EndToEndAgent} against a scripted fake model,
 * proving the subsystems compose into a working run without any network.
 */
class EndToEndAgentTest {

    private static final String MODEL = "fake-model";

    @Test
    void groundsAnswerInKnowledgeThenPassesVerification() {
        // Turn 1: search the knowledge base. Turn 2: answer. Then the verifier passes.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("t1", "knowledge_search",
                        Map.of("query", "return final-sale item")))
                .then(FakeLlm.text("Final-sale items are not returnable, even within 30 days."))
                .then(FakeLlm.text("PASS"));

        SelfVerifyingAgent agent = EndToEndAgent.build(llm, MODEL);
        AgentResult result = agent.run(Goal.of(
                "Can a customer return a final-sale item bought 10 days ago?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("not returnable");
    }

    @Test
    void revealsAndUsesADeferredToolViaSearch() {
        // order_lookup is deferred; the model finds it via search_tools, then calls it.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("s1", "search_tools", Map.of("query", "order status")))
                .then(FakeLlm.toolUse("t1", "order_lookup", Map.of("orderId", "123")))
                .then(FakeLlm.text("Your order 123 has shipped."))
                .then(FakeLlm.text("PASS"));

        SelfVerifyingAgent agent = EndToEndAgent.build(llm, MODEL);
        AgentResult result = agent.run(Goal.of("What is the status of order 123?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("shipped");
    }

    @Test
    void retriesWhenVerificationFailsThenSucceeds() {
        // First attempt answers but the critic FAILs; second attempt passes.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.text("Final-sale items can be returned."))     // attempt 1 answer
                .then(FakeLlm.text("FAIL\nThat contradicts the returns policy.")) // critic fails it
                .then(FakeLlm.text("Final-sale items are not returnable."))   // attempt 2 answer
                .then(FakeLlm.text("PASS"));                                   // critic passes

        SelfVerifyingAgent agent = EndToEndAgent.build(llm, MODEL);
        AgentResult result = agent.run(Goal.of("Are final-sale items returnable?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("not returnable");
    }
}
