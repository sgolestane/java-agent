package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;

/**
 * Decides whether a gated tool invocation is approved. In an unsupervised setting
 * the safe default is {@link #DENY_ALL}; interactive harnesses can prompt a human.
 */
@FunctionalInterface
public interface ConfirmationHandler {

    /** Denies every request — the safe default for unsupervised runs. */
    ConfirmationHandler DENY_ALL = invocation -> false;

    /** Approves every request. */
    ConfirmationHandler ALLOW_ALL = invocation -> true;

    /** Returns whether {@code invocation} is approved to run. */
    boolean confirm(ToolInvocation invocation);
}
