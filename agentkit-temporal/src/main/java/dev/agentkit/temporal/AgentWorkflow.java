package dev.agentkit.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The AgentKit agent loop as a Temporal workflow. The same goal-in / result-out
 * contract as the in-process {@code Agent}, but every model turn and tool call is
 * an activity, so the run is durable: it survives worker crashes and replays
 * deterministically from history without re-executing completed activities.
 */
@WorkflowInterface
public interface AgentWorkflow {

    /** Pursues {@link DurableAgentRun#goal()} to completion, durably. */
    @WorkflowMethod
    AgentRunResult run(DurableAgentRun run);
}
