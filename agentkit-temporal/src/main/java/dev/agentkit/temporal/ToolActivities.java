package dev.agentkit.temporal;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The tool-execution boundary as a Temporal activity. Each tool call the model
 * requests runs as its own activity, so its side effect happens exactly once per
 * logical step and is memoized in history.
 */
@ActivityInterface
public interface ToolActivities {

    /** Executes one tool invocation and returns its result. */
    @ActivityMethod
    ToolResult executeTool(ToolInvocation invocation);
}
