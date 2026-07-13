package dev.agentkit.core.agent;

/**
 * Why an agent run ended.
 */
public enum StopReason {
    /** The agent produced a final answer and considers the goal complete. */
    COMPLETED,
    /** The configured maximum number of steps was reached. */
    MAX_STEPS,
    /** The configured token or cost budget was exhausted. */
    BUDGET_EXHAUSTED,
    /** A verification or guardrail check rejected the outcome and no recovery was possible. */
    VERIFICATION_FAILED,
    /** The model or a safety layer refused to continue. */
    REFUSED,
    /** An unrecoverable error occurred while running the agent. */
    ERROR,
    /** The run was cancelled by the caller. */
    CANCELLED
}
