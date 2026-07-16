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
     *
     * <p>The {@code AgentObserver} reports the tool invocation the model
     * <em>proposed</em>, which fires before any gate runs — so when an approver edits
     * arguments, the observed invocation is the original, not the one that executes.
     * The approver itself is the audit point for edits: it sees the proposal and
     * decides the change, so log there if you need an approval trail.
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
     *
     * <p>Gates run in order, and a gate that approves with edited arguments (see
     * {@link #requireApproval}) is honored: each later gate evaluates the edited
     * invocation, and the combined result carries the final edit. The first denial
     * short-circuits and is returned as-is.
     */
    public static ToolGate allOf(ToolGate... gates) {
        List<ToolGate> all = List.of(gates);
        return invocation -> {
            ToolInvocation effective = invocation;
            boolean edited = false;
            for (ToolGate gate : all) {
                GateResult result = gate.evaluate(effective);
                if (!result.allowed()) {
                    return result;
                }
                if (result.replacement().isPresent()) {
                    effective = result.replacement().get();
                    edited = true;
                }
            }
            return edited ? GateResult.allowWith(effective) : GateResult.allow();
        };
    }
}
