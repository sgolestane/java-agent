package dev.agentkit.temporal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import java.util.Objects;

/**
 * The serializable outcome of a durable agent run, returned by the workflow.
 *
 * <p>Mirrors the in-process {@code AgentResult} but replaces its
 * {@code Optional<Throwable>} with a plain {@code errorMessage} string so the
 * result serializes cleanly across the workflow boundary (a {@code Throwable} is
 * not portably serializable, and durable failures surface through Temporal's own
 * failure model rather than an embedded exception).
 *
 * @param stopReason   why the run ended
 * @param output       the final textual output; never {@code null} (may be empty)
 * @param steps        the number of model turns taken
 * @param usage        cumulative token usage; never {@code null}
 * @param errorMessage a failure message when {@code stopReason == ERROR};
 *                     otherwise empty
 */
public record AgentRunResult(StopReason stopReason, String output, int steps, TokenUsage usage,
                             String errorMessage) {

    public AgentRunResult {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(errorMessage, "errorMessage");
    }

    /** Derived, not a serialized field — ignored so the record round-trips cleanly. */
    @JsonIgnore
    public boolean isSuccess() {
        return stopReason == StopReason.COMPLETED;
    }

    static AgentRunResult of(StopReason reason, String output, int steps, TokenUsage usage) {
        return new AgentRunResult(reason, output, steps, usage, "");
    }

    /** Adapts an in-process {@link AgentResult} to its serializable form. */
    public static AgentRunResult from(AgentResult result) {
        String message = result.error().map(Throwable::getMessage).orElse("");
        return new AgentRunResult(result.stopReason(), result.output(), result.steps(),
                result.usage(), message == null ? "" : message);
    }
}
