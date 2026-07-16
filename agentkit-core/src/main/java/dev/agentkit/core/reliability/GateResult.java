package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;
import java.util.Objects;
import java.util.Optional;

/**
 * The decision of a {@link ToolGate}: whether a tool invocation may proceed, and
 * if not, the reason returned to the model.
 *
 * <p>An allowed result may also carry a {@code replacement} invocation — the same
 * tool with edited arguments — which the agent loop runs in place of the original.
 * This lets an {@link Approver} approve an action while adjusting its parameters.
 *
 * @param allowed     whether execution is permitted
 * @param reason      when denied, an explanation surfaced to the model; empty when allowed
 * @param replacement when allowed, an optional substitute invocation to run instead
 *                    of the one the model proposed; empty to run the original
 */
public record GateResult(boolean allowed, String reason, Optional<ToolInvocation> replacement) {

    private static final GateResult ALLOW = new GateResult(true, "", Optional.empty());

    public GateResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(replacement, "replacement");
        if (!allowed && replacement.isPresent()) {
            throw new IllegalArgumentException("a denied result cannot carry a replacement invocation");
        }
    }

    public static GateResult allow() {
        return ALLOW;
    }

    /** Allows execution, but substitutes {@code replacement} for the proposed invocation. */
    public static GateResult allowWith(ToolInvocation replacement) {
        return new GateResult(true, "", Optional.of(Objects.requireNonNull(replacement, "replacement")));
    }

    public static GateResult deny(String reason) {
        return new GateResult(false, Objects.requireNonNull(reason, "reason"), Optional.empty());
    }
}
