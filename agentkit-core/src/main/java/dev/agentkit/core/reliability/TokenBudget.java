package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A cap on how many tokens (or how much money) an agent run may spend, enforced by
 * {@link BudgetLlmClient}.
 *
 * <p>A budget may combine several caps — input tokens, output tokens, total tokens,
 * and estimated USD cost. It is <em>exhausted</em> as soon as the cumulative spend
 * meets or exceeds any one of them. Because the caller cannot know a turn's cost
 * before making it, the check is applied to the spend accumulated <em>so far</em>:
 * the turn that first reaches the cap still completes, and the next turn is the one
 * that is refused. See {@link BudgetLlmClient} for the loop-level effect.
 *
 * <p>Build one with {@link #builder()} or a convenience factory:
 * <pre>{@code
 * TokenBudget.ofTotalTokens(1_000_000);
 * TokenBudget.ofCostUsd(5.00, ModelPricing.of(5.00, 25.00));
 * TokenBudget.builder()
 *         .maxTotalTokens(1_000_000)
 *         .maxCostUsd(5.00, ModelPricing.of(5.00, 25.00))
 *         .build();
 * }</pre>
 */
public final class TokenBudget {

    private final long maxInputTokens;
    private final long maxOutputTokens;
    private final long maxTotalTokens;
    private final double maxCostUsd;
    private final ModelPricing pricing;

    private TokenBudget(Builder b) {
        this.maxInputTokens = b.maxInputTokens;
        this.maxOutputTokens = b.maxOutputTokens;
        this.maxTotalTokens = b.maxTotalTokens;
        this.maxCostUsd = b.maxCostUsd;
        this.pricing = b.pricing;
    }

    /** A budget capped at {@code max} total (input + output) tokens. */
    public static TokenBudget ofTotalTokens(long max) {
        return builder().maxTotalTokens(max).build();
    }

    /** A budget capped at {@code maxUsd} of estimated cost at {@code pricing}. */
    public static TokenBudget ofCostUsd(double maxUsd, ModelPricing pricing) {
        return builder().maxCostUsd(maxUsd, pricing).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether {@code spent} has met or exceeded any configured cap. */
    public boolean isExhausted(TokenUsage spent) {
        return breach(spent).isPresent();
    }

    /**
     * A human description of the first cap {@code spent} has met or exceeded, or
     * empty if the budget still has headroom. Used for the stop-reason message.
     */
    Optional<String> breach(TokenUsage spent) {
        if (maxTotalTokens > 0 && spent.totalTokens() >= maxTotalTokens) {
            return Optional.of(spent.totalTokens() + " total tokens >= cap " + maxTotalTokens);
        }
        if (maxInputTokens > 0 && spent.inputTokens() >= maxInputTokens) {
            return Optional.of(spent.inputTokens() + " input tokens >= cap " + maxInputTokens);
        }
        if (maxOutputTokens > 0 && spent.outputTokens() >= maxOutputTokens) {
            return Optional.of(spent.outputTokens() + " output tokens >= cap " + maxOutputTokens);
        }
        if (maxCostUsd > 0) {
            double cost = pricing.costOf(spent);
            if (cost >= maxCostUsd) {
                return Optional.of(String.format("estimated cost $%.4f >= cap $%.4f", cost, maxCostUsd));
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        List<String> caps = new ArrayList<>();
        if (maxInputTokens > 0) {
            caps.add("maxInputTokens=" + maxInputTokens);
        }
        if (maxOutputTokens > 0) {
            caps.add("maxOutputTokens=" + maxOutputTokens);
        }
        if (maxTotalTokens > 0) {
            caps.add("maxTotalTokens=" + maxTotalTokens);
        }
        if (maxCostUsd > 0) {
            caps.add(String.format("maxCostUsd=%.4f", maxCostUsd));
        }
        return "TokenBudget[" + String.join(", ", caps) + "]";
    }

    /** Fluent construction. At least one positive cap must be set. */
    public static final class Builder {
        private long maxInputTokens;
        private long maxOutputTokens;
        private long maxTotalTokens;
        private double maxCostUsd;
        private ModelPricing pricing;

        private Builder() {
        }

        /** Cap on cumulative input (prompt) tokens; ignored if {@code <= 0}. */
        public Builder maxInputTokens(long max) {
            this.maxInputTokens = max;
            return this;
        }

        /** Cap on cumulative output (completion) tokens; ignored if {@code <= 0}. */
        public Builder maxOutputTokens(long max) {
            this.maxOutputTokens = max;
            return this;
        }

        /** Cap on cumulative total (input + output) tokens; ignored if {@code <= 0}. */
        public Builder maxTotalTokens(long max) {
            this.maxTotalTokens = max;
            return this;
        }

        /**
         * Cap on cumulative estimated cost in USD, priced with {@code pricing}.
         *
         * @throws NullPointerException if {@code pricing} is null
         */
        public Builder maxCostUsd(double maxUsd, ModelPricing pricing) {
            this.maxCostUsd = maxUsd;
            this.pricing = java.util.Objects.requireNonNull(pricing, "pricing");
            return this;
        }

        public TokenBudget build() {
            boolean hasTokenCap = maxInputTokens > 0 || maxOutputTokens > 0 || maxTotalTokens > 0;
            boolean hasCostCap = maxCostUsd > 0;
            if (!hasTokenCap && !hasCostCap) {
                throw new IllegalStateException("a TokenBudget needs at least one positive cap");
            }
            if (hasCostCap && pricing == null) {
                throw new IllegalStateException("a cost cap requires ModelPricing");
            }
            return new TokenBudget(this);
        }
    }
}
