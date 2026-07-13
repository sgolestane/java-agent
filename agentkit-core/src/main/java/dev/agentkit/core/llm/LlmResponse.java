package dev.agentkit.core.llm;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import java.util.Objects;

/**
 * A provider-agnostic response from an {@link LlmClient}.
 *
 * @param message    the assistant message produced by the model; its role must
 *                   be {@link Role#ASSISTANT}
 * @param stopReason the normalised reason the turn ended; never {@code null}
 * @param usage      token accounting for the call; never {@code null}
 */
public record LlmResponse(Message message, LlmStopReason stopReason, TokenUsage usage) {

    public LlmResponse {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
        if (message.role() != Role.ASSISTANT) {
            throw new IllegalArgumentException(
                    "LLM response message must have role ASSISTANT, was " + message.role());
        }
    }

    /** Whether the model requested tool execution. */
    public boolean requestsTools() {
        return stopReason == LlmStopReason.TOOL_USE;
    }
}
