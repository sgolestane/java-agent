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
     * Requires a yes/no confirmation for invocations matching {@code gatedWhen}.
     * Non-matching invocations are allowed; matching ones are allowed only if
     * {@code handler} approves, otherwise denied. A convenience over
     * {@link #requireApproval} for the common boolean case.
     */
    public static ToolGate requireConfirmation(Predicate<ToolInvocation> gatedWhen,
                                               ConfirmationHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return requireApproval(gatedWhen, invocation -> handler.confirm(invocation)
                ? ApprovalDecision.approve()
                : ApprovalDecision.deny("This action requires confirmation and was not approved."));
    }

    /**
     * Routes invocations matching {@code gatedWhen} through {@code approver} for a
     * human-in-the-loop decision. Non-matching invocations are allowed unchanged;
     * matching ones are approved, denied with the approver's reason, or approved
     * with the approver's edited arguments (see {@link ApprovalDecision}).
     */
    public static ToolGate requireApproval(Predicate<ToolInvocation> gatedWhen, Approver approver) {
        Objects.requireNonNull(gatedWhen, "gatedWhen");
        Objects.requireNonNull(approver, "approver");
        return invocation -> {
            if (!gatedWhen.test(invocation)) {
                return GateResult.allow();
            }
            return approver.review(invocation).toGateResult(invocation);
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
