package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.TokenUsage;

/**
 * Per-token pricing for a model, used to turn a {@link TokenUsage} into a dollar
 * cost for cost-capped {@link TokenBudget budgets}.
 *
 * <p>Prices are quoted per million tokens — the unit model vendors publish — so a
 * model that costs $5.00 per million input tokens and $25.00 per million output
 * tokens is {@code ModelPricing.of(5.00, 25.00)}. Cached reads and other tiers are
 * not modelled; this is a deliberately simple estimate for budgeting, not billing.
 *
 * @param inputPerMillionUsd  USD charged per 1,000,000 input (prompt) tokens
 * @param outputPerMillionUsd USD charged per 1,000,000 output (completion) tokens
 */
public record ModelPricing(double inputPerMillionUsd, double outputPerMillionUsd) {

    public ModelPricing {
        if (inputPerMillionUsd < 0 || outputPerMillionUsd < 0) {
            throw new IllegalArgumentException("prices must be >= 0");
        }
    }

    /** Pricing in USD per million tokens. */
    public static ModelPricing of(double inputPerMillionUsd, double outputPerMillionUsd) {
        return new ModelPricing(inputPerMillionUsd, outputPerMillionUsd);
    }

    /** The estimated USD cost of {@code usage} at this pricing. */
    public double costOf(TokenUsage usage) {
        return usage.inputTokens() / 1_000_000.0 * inputPerMillionUsd
                + usage.outputTokens() / 1_000_000.0 * outputPerMillionUsd;
    }
}
