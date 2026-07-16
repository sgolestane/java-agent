package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A human (or policy) verdict on a proposed tool invocation, produced by an
 * {@link Approver} and applied by {@link ToolGates#requireApproval}.
 *
 * <p>Three outcomes model the way a reviewer actually handles a risky action:
 * <ul>
 *   <li>{@link #approve()} — run it as the model proposed;</li>
 *   <li>{@link #deny(String)} — reject it, with a reason surfaced to the model so it
 *       can adapt (the plain allow/deny gate can only return a fixed message);</li>
 *   <li>{@link #approveWithArguments(Map)} — run it, but with arguments the reviewer
 *       edited (e.g. narrowing a destructive scope or fixing a bad parameter).</li>
 * </ul>
 */
public final class ApprovalDecision {

    /** Which of the three outcomes this decision represents. */
    public enum Kind { APPROVE, DENY, APPROVE_WITH_ARGUMENTS }

    private static final ApprovalDecision APPROVE = new ApprovalDecision(Kind.APPROVE, "", null);

    private final Kind kind;
    private final String reason;
    private final Map<String, Object> arguments;

    private ApprovalDecision(Kind kind, String reason, Map<String, Object> arguments) {
        this.kind = kind;
        this.reason = reason;
        this.arguments = arguments;
    }

    /** Approve the invocation unchanged. */
    public static ApprovalDecision approve() {
        return APPROVE;
    }

    /**
     * Reject the invocation. The {@code reason} is returned to the model as the tool
     * result, so it can explain the failure or try a different approach.
     */
    public static ApprovalDecision deny(String reason) {
        return new ApprovalDecision(Kind.DENY, Objects.requireNonNull(reason, "reason"), null);
    }

    /**
     * Approve the invocation, but replace its arguments with {@code arguments}. The
     * tool then runs with the edited values; its id and name are unchanged.
     *
     * <p>This is a <em>full</em> replacement, not a merge: {@code arguments} becomes
     * the entire argument map. When several approval gates are composed with
     * {@link ToolGates#allOf}, each sees the prior gate's edited invocation, so an
     * approver that wants to keep earlier edits should build its map from the
     * invocation it receives ({@code invocation.arguments()}) rather than from
     * scratch — otherwise it silently drops them.
     *
     * @throws NullPointerException if {@code arguments} is null
     */
    public static ApprovalDecision approveWithArguments(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        return new ApprovalDecision(Kind.APPROVE_WITH_ARGUMENTS, "", copy);
    }

    public Kind kind() {
        return kind;
    }

    /** The denial reason; empty unless this is a {@link Kind#DENY} decision. */
    public String reason() {
        return reason;
    }

    /**
     * The replacement arguments for a {@link Kind#APPROVE_WITH_ARGUMENTS} decision.
     *
     * @throws IllegalStateException if this is not an edited-arguments decision
     */
    public Map<String, Object> arguments() {
        if (kind != Kind.APPROVE_WITH_ARGUMENTS) {
            throw new IllegalStateException("no arguments on a " + kind + " decision");
        }
        return arguments;
    }

    /** Turns this decision into the gate result the agent loop enforces. */
    GateResult toGateResult(ToolInvocation invocation) {
        return switch (kind) {
            case APPROVE -> GateResult.allow();
            case DENY -> GateResult.deny(reason);
            case APPROVE_WITH_ARGUMENTS -> GateResult.allowWith(
                    new ToolInvocation(invocation.id(), invocation.name(), arguments));
        };
    }
}
