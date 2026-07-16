package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BudgetLlmClientTest {

    private static final LlmRequest REQUEST =
            LlmRequest.builder("m").addMessage(Message.user("hi")).build();

    /** Returns a response carrying {@code usage} on every call, counting calls. */
    private static LlmClient usageEach(TokenUsage usage, AtomicInteger calls) {
        return request -> {
            calls.incrementAndGet();
            return FakeLlmClient.textWithUsage("ok", usage);
        };
    }

    @Test
    void passesThroughAndAccumulatesUsageWhileUnderBudget() {
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100, 50), calls), TokenBudget.ofTotalTokens(1000));

        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok");
        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(client.spent()).isEqualTo(new TokenUsage(200, 100));
    }

    @Test
    void refusesTheCallAfterTheBudgetIsReached() {
        AtomicInteger calls = new AtomicInteger();
        // Cap of 150 total; each call spends 100. First call is allowed (spend 0 < 150),
        // pushing the total to 100. Second is allowed (100 < 150), pushing it to 200.
        // Third is refused because 200 >= 150.
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(60, 40), calls), TokenBudget.ofTotalTokens(150));

        client.generate(REQUEST);
        client.generate(REQUEST);
        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("200 total tokens >= cap 150");
        assertThat(calls).hasValue(2); // the refused call never reached the delegate
    }

    @Test
    void budgetExceededExceptionCarriesTheSpend() {
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100, 100), new AtomicInteger()), TokenBudget.ofTotalTokens(100));

        client.generate(REQUEST); // spend now 200 >= 100
        try {
            client.generate(REQUEST);
            org.junit.jupiter.api.Assertions.fail("expected BudgetExceededException");
        } catch (BudgetExceededException e) {
            assertThat(e.spent()).isEqualTo(new TokenUsage(100, 100));
        }
    }

    @Test
    void refusesImmediatelyWhenTheFirstCallWouldExceedNothing() {
        // A tiny cap is only tripped after spend is recorded, so the very first call
        // (spend == 0) always runs; the guard is a pre-flight check on prior spend.
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(1, 0), calls), TokenBudget.ofTotalTokens(1));

        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok"); // allowed
        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void costBudgetStopsWhenEstimatedSpendReachesTheCap() {
        // $5/M in, $25/M out. 100k in + 20k out = 100k/1M*5 + 20k/1M*25 = 0.5 + 0.5 = $1.00.
        // With an $0.80 cap, the first call runs (spend $0), pushing the total to $1.00;
        // the second is refused because $1.00 >= $0.80.
        ModelPricing pricing = ModelPricing.of(5.00, 25.00);
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100_000, 20_000), calls), TokenBudget.ofCostUsd(0.80, pricing));

        client.generate(REQUEST); // $1.00 spent, now over the $0.80 cap
        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("cost");
        assertThat(calls).hasValue(1);
    }
}
