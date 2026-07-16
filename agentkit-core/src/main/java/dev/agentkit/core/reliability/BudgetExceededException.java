package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.TokenUsage;

/**
 * Thrown by {@link BudgetLlmClient} when a model call is refused because the run's
 * {@link TokenBudget} is already exhausted.
 *
 * <p>This is a deliberate stop, not a failure: it does <em>not</em> extend
 * {@code LlmException}, so retry decorators leave it alone, and the agent loop maps
 * it to a {@code BUDGET_EXHAUSTED} stop rather than an error result.
 */
public final class BudgetExceededException extends RuntimeException {

    private final transient TokenUsage spent;

    public BudgetExceededException(TokenBudget budget, TokenUsage spent) {
        super("Token budget exhausted: " + budget.breach(spent).orElse(budget.toString()));
        this.spent = spent;
    }

    /** The cumulative usage that tripped the budget. */
    public TokenUsage spent() {
        return spent;
    }
}
