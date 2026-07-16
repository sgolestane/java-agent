package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.TokenUsage;
import org.junit.jupiter.api.Test;

class TokenBudgetTest {

    @Test
    void totalTokenCapTripsWhenReachedOrExceeded() {
        TokenBudget budget = TokenBudget.ofTotalTokens(100);
        assertThat(budget.isExhausted(new TokenUsage(40, 40))).isFalse(); // 80 < 100
        assertThat(budget.isExhausted(new TokenUsage(50, 50))).isTrue();  // 100 >= 100
        assertThat(budget.isExhausted(new TokenUsage(90, 90))).isTrue();  // 180 >= 100
    }

    @Test
    void separateInputAndOutputCapsAreCheckedIndependently() {
        TokenBudget budget = TokenBudget.builder()
                .maxInputTokens(100)
                .maxOutputTokens(10)
                .build();
        assertThat(budget.isExhausted(new TokenUsage(50, 5))).isFalse();
        // Output cap alone is enough to exhaust even though input has headroom.
        assertThat(budget.isExhausted(new TokenUsage(50, 10))).isTrue();
        assertThat(budget.breach(new TokenUsage(50, 10)).orElseThrow()).contains("output tokens");
    }

    @Test
    void costCapUsesPricingToConvertTokensToDollars() {
        TokenBudget budget = TokenBudget.ofCostUsd(1.00, ModelPricing.of(5.00, 25.00));
        // 100k in = $0.50, 20k out = $0.50 => $1.00 exactly, which meets the cap.
        assertThat(budget.isExhausted(new TokenUsage(100_000, 20_000))).isTrue();
        assertThat(budget.isExhausted(new TokenUsage(99_000, 19_000))).isFalse();
        assertThat(budget.breach(new TokenUsage(100_000, 20_000)).orElseThrow()).contains("cost");
    }

    @Test
    void breachReportsTheFirstCapHit() {
        TokenBudget budget = TokenBudget.ofTotalTokens(50);
        assertThat(budget.breach(TokenUsage.ZERO)).isEmpty();
        assertThat(budget.breach(new TokenUsage(30, 30)).orElseThrow()).contains("total tokens");
    }

    @Test
    void builderRejectsAnEmptyBudget() {
        assertThatThrownBy(() -> TokenBudget.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one positive cap");
    }

    @Test
    void nonPositiveCapsAreIgnored() {
        // A zero/negative cap is treated as "unset", so this budget is driven only
        // by the total-token cap.
        TokenBudget budget = TokenBudget.builder()
                .maxInputTokens(0)
                .maxOutputTokens(-5)
                .maxTotalTokens(100)
                .build();
        assertThat(budget.isExhausted(new TokenUsage(200, 0))).isTrue();
        assertThat(budget.breach(new TokenUsage(60, 60)).orElseThrow()).contains("total tokens");
    }

    @Test
    void modelPricingComputesCost() {
        ModelPricing pricing = ModelPricing.of(5.00, 25.00);
        assertThat(pricing.costOf(new TokenUsage(1_000_000, 0))).isEqualTo(5.00);
        assertThat(pricing.costOf(new TokenUsage(0, 1_000_000))).isEqualTo(25.00);
        assertThat(pricing.costOf(TokenUsage.ZERO)).isEqualTo(0.0);
    }

    @Test
    void modelPricingRejectsNegativePrices() {
        assertThatThrownBy(() -> ModelPricing.of(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
