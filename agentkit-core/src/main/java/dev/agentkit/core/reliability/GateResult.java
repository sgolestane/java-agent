package dev.agentkit.core.reliability;

import java.util.Objects;

/**
 * The decision of a {@link ToolGate}: whether a tool invocation may proceed, and
 * if not, the reason returned to the model.
 *
 * @param allowed whether execution is permitted
 * @param reason  when denied, an explanation surfaced to the model; empty when allowed
 */
public record GateResult(boolean allowed, String reason) {

    private static final GateResult ALLOW = new GateResult(true, "");

    public GateResult {
        Objects.requireNonNull(reason, "reason");
    }

    public static GateResult allow() {
        return ALLOW;
    }

    public static GateResult deny(String reason) {
        return new GateResult(false, Objects.requireNonNull(reason, "reason"));
    }
}
