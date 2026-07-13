package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Factories and combinators for common {@link ToolGate}s.
 */
public final class ToolGates {

    private ToolGates() {
    }

    public static ToolGate allowAll() {
        return ToolGate.ALLOW_ALL;
    }

    /** Denies invocations matching {@code predicate} with {@code reason}. */
    public static ToolGate denyIf(Predicate<ToolInvocation> predicate, String reason) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(reason, "reason");
        return invocation -> predicate.test(invocation) ? GateResult.deny(reason) : GateResult.allow();
    }

    /** Denies any invocation of a tool whose name is in {@code toolNames}. */
    public static ToolGate denyTools(Set<String> toolNames) {
        Objects.requireNonNull(toolNames, "toolNames");
        Set<String> names = Set.copyOf(toolNames);
        return denyIf(inv -> names.contains(inv.name()),
                "This tool is blocked by policy and cannot be used.");
    }

    /**
     * Requires confirmation for invocations matching {@code gatedWhen}. Non-matching
     * invocations are allowed; matching ones are allowed only if {@code handler}
     * approves, otherwise denied.
     */
    public static ToolGate requireConfirmation(Predicate<ToolInvocation> gatedWhen,
                                               ConfirmationHandler handler) {
        Objects.requireNonNull(gatedWhen, "gatedWhen");
        Objects.requireNonNull(handler, "handler");
        return invocation -> {
            if (!gatedWhen.test(invocation)) {
                return GateResult.allow();
            }
            return handler.confirm(invocation)
                    ? GateResult.allow()
                    : GateResult.deny("This action requires confirmation and was not approved.");
        };
    }

    /**
     * Combines gates: the invocation is allowed only if <em>every</em> gate allows
     * it. With no gates the result allows everything (fail-open) — an empty policy
     * imposes no restriction, matching the identity of "allow unless denied".
     */
    public static ToolGate allOf(ToolGate... gates) {
        List<ToolGate> all = List.of(gates);
        return invocation -> {
            for (ToolGate gate : all) {
                GateResult result = gate.evaluate(invocation);
                if (!result.allowed()) {
                    return result;
                }
            }
            return GateResult.allow();
        };
    }
}
