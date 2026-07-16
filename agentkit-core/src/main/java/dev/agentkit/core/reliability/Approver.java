package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;

/**
 * Reviews a proposed tool invocation and returns an {@link ApprovalDecision} —
 * the human-in-the-loop seam for approving, rejecting, or editing hard-to-reverse
 * actions before they run. Wire one in with {@link ToolGates#requireApproval}.
 *
 * <p>An interactive harness implements this by prompting a person (CLI, chat, web);
 * unattended runs use {@link #DENY_ALL} to fail safe or a policy that approves only
 * known-safe shapes. The review runs on the agent thread, so a blocking prompt
 * blocks the loop — for asynchronous or durable approval, resolve the decision
 * elsewhere and hand this a ready answer.
 */
@FunctionalInterface
public interface Approver {

    /** Approves every invocation unchanged. */
    Approver APPROVE_ALL = invocation -> ApprovalDecision.approve();

    /** Denies every invocation — the safe default for unattended runs. */
    Approver DENY_ALL = invocation ->
            ApprovalDecision.deny("This action requires human approval, which was not granted.");

    /** Reviews {@code invocation} and returns the verdict to enforce. */
    ApprovalDecision review(ToolInvocation invocation);
}
