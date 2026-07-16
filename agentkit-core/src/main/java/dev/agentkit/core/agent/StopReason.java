package dev.agentkit.core.agent;

/**
 * Why an agent run ended.
 */
public enum StopReason {
    /** The agent produced a final answer and considers the goal complete. */
    COMPLETED,
    /** The configured maximum number of steps was reached. */
    MAX_STEPS,
    /** The configured cumulative token or cost budget was exhausted (see {@code BudgetLlmClient}). */
    BUDGET_EXHAUSTED,
    /**
     * A single turn's output was truncated at the per-call {@code maxTokens} limit.
     * Distinct from {@link #BUDGET_EXHAUSTED}, which is a run-wide spend cap: this is
     * one response cut short, so any tool call in that turn may be incomplete.
     */
    OUTPUT_TRUNCATED,
    /** A verification or guardrail check rejected the outcome and no recovery was possible. */
    VERIFICATION_FAILED,
    /** The model or a safety layer refused to continue. */
    REFUSED,
    /** The turn paused (e.g. a long-running server-side tool) and the run does not support resuming it. */
    PAUSED,
    /** An unrecoverable error occurred while running the agent. */
    ERROR,
    /** The run was cancelled by the caller. */
    CANCELLED
}
