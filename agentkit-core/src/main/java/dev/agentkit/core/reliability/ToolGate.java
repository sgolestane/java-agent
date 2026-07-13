package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;

/**
 * A guardrail evaluated before a tool runs. Gates let a harness block or approve
 * hard-to-reverse actions (external writes, sends, deletes) that an unsupervised
 * agent should not take unilaterally — a denied call becomes an error result the
 * model can react to, not a silent no-op.
 */
@FunctionalInterface
public interface ToolGate {

    /** A gate that permits every invocation. */
    ToolGate ALLOW_ALL = invocation -> GateResult.allow();

    /** Decides whether {@code invocation} may execute. */
    GateResult evaluate(ToolInvocation invocation);
}
