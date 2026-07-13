package dev.agentkit.core.agent;

import dev.agentkit.core.llm.TokenUsage;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of an agent run.
 *
 * @param stopReason why the run ended; never {@code null}
 * @param output     the final textual output produced by the agent; never
 *                   {@code null} (may be empty)
 * @param steps      the number of reasoning/tool steps taken
 * @param usage      cumulative token usage across the run; never {@code null}
 * @param error      the error that ended the run, if {@link StopReason#ERROR};
 *                   otherwise empty
 */
public record AgentResult(StopReason stopReason, String output, int steps, TokenUsage usage,
                          Optional<Throwable> error) {

    public AgentResult {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(error, "error");
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0, was " + steps);
        }
        if (error.isPresent() != (stopReason == StopReason.ERROR)) {
            throw new IllegalArgumentException(
                    "error must be present if and only if stopReason is ERROR (stopReason="
                            + stopReason + ", error present=" + error.isPresent() + ")");
        }
    }

    public boolean isSuccess() {
        return stopReason == StopReason.COMPLETED;
    }

    public static AgentResult completed(String output, int steps) {
        return completed(output, steps, TokenUsage.ZERO);
    }

    public static AgentResult completed(String output, int steps, TokenUsage usage) {
        return new AgentResult(StopReason.COMPLETED, output, steps, usage, Optional.empty());
    }

    /**
     * Builds a non-error stop result. Use {@link #completed(String, int)} for a
     * successful finish and {@link #failed(Throwable, int)} for an error.
     *
     * @throws IllegalArgumentException if {@code reason} is {@link StopReason#ERROR}
     */
    public static AgentResult stopped(StopReason reason, String output, int steps) {
        return stopped(reason, output, steps, TokenUsage.ZERO);
    }

    public static AgentResult stopped(StopReason reason, String output, int steps, TokenUsage usage) {
        Objects.requireNonNull(reason, "reason");
        if (reason == StopReason.ERROR) {
            throw new IllegalArgumentException("Use failed(Throwable, int) to build an ERROR result");
        }
        return new AgentResult(reason, output, steps, usage, Optional.empty());
    }

    public static AgentResult failed(Throwable error, int steps) {
        return failed(error, steps, TokenUsage.ZERO);
    }

    public static AgentResult failed(Throwable error, int steps, TokenUsage usage) {
        Objects.requireNonNull(error, "error");
        return new AgentResult(StopReason.ERROR, "", steps, usage, Optional.of(error));
    }
}
