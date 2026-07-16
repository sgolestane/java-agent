package dev.agentkit.core.agent;

import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;

/**
 * A hook into the agent loop for observability and steering. All methods have
 * no-op defaults, so implementations override only what they need.
 *
 * <p>Later phases layer verification, gating, and durable-execution concerns on
 * top of this seam.
 */
public interface AgentObserver {

    /** A no-op observer. */
    AgentObserver NONE = new AgentObserver() {
    };

    /** Called once when a run begins. */
    default void onStart(Goal goal) {
    }

    /**
     * Called with each incremental text fragment while a model turn streams in.
     * Only fires when the agent is built with streaming enabled; {@code delta} is a
     * fragment to concatenate, and {@link #onModelResponse} still delivers the
     * completed turn afterward.
     */
    default void onTextDelta(int step, String delta) {
    }

    /** Called after each model turn completes. */
    default void onModelResponse(int step, LlmResponse response) {
    }

    /** Called before a tool is executed. */
    default void onToolInvocation(int step, ToolInvocation invocation) {
    }

    /** Called after a tool returns (or fails and produces an error result). */
    default void onToolResult(int step, ToolInvocation invocation, ToolResult result) {
    }

    /** Called once when a run ends, for any reason. */
    default void onFinish(AgentResult result) {
    }
}
