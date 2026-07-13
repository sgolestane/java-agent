package dev.agentkit.temporal;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The tool-execution boundary as a Temporal activity. Each tool call the model
 * requests runs as its own activity; once it completes, its result is memoized in
 * history and never recomputed on replay.
 *
 * <p><strong>Tools must be idempotent under retry.</strong> Temporal activities
 * are <em>at-least-once</em>: an infrastructure fault (a start-to-close timeout, or
 * a worker crash after the side effect but before the result is recorded) re-runs
 * the activity, so a non-idempotent side effect (charging a card, sending an
 * email) can execute more than once. The in-process loop never retries tools, so
 * the durable path introduces this risk. For a non-idempotent tool, set its
 * {@code toolMaxAttempts} to 1 (see {@link DurableAgentOptions}) or make the tool
 * idempotent (e.g. key the side effect by the invocation id).
 */
@ActivityInterface
public interface ToolActivities {

    /** Executes one tool invocation and returns its result. */
    @ActivityMethod
    ToolResult executeTool(ToolInvocation invocation);
}
