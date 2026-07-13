package dev.agentkit.temporal;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Worker-side {@link ToolActivities} implementation that resolves and runs tools
 * from a core {@link ToolRegistry}.
 *
 * <p>Matching the in-process agent loop, a tool failure is turned into an error
 * {@link ToolResult} the model reacts to (the activity <em>succeeds</em> with an
 * error result) rather than a thrown exception — so a deterministic tool error
 * does not trigger pointless Temporal retries. To make a specific tool's
 * transient faults retryable instead, have that tool throw and register a
 * separate activity for it.
 */
public final class ToolActivitiesImpl implements ToolActivities {

    private final ToolRegistry tools;

    public ToolActivitiesImpl(ToolRegistry tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public ToolResult executeTool(ToolInvocation invocation) {
        Optional<Tool> tool = tools.find(invocation.name());
        if (tool.isEmpty()) {
            return ToolResult.error("Unknown tool: '" + invocation.name() + "'");
        }
        try {
            return tool.get().execute(invocation);
        } catch (RuntimeException e) {
            return ToolResult.error("Tool '" + invocation.name() + "' failed: " + e.getMessage());
        }
    }
}
