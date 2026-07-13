package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes a {@link SubagentRoster} to a supervisor {@link
 * dev.agentkit.core.agent.Agent} as a {@code delegate} tool, enabling
 * <em>model-driven</em> decomposition: the supervisor's model reads the roster
 * catalog, then calls {@code delegate(subagent, goal)} one subgoal at a time,
 * reacting to each result before deciding the next.
 *
 * <p>This complements {@link Supervisor#fanOut} (programmatic, parallel
 * decomposition). Use the tool when the split depends on intermediate results;
 * use {@code fanOut} when the independent subgoals are known up front.
 *
 * <p>Delegation runs synchronously and the subagent's output is returned to the
 * supervisor as the tool result. A failed delegation comes back as an error
 * result carrying the stop reason, so the supervisor can retry, route elsewhere,
 * or report the gap rather than silently continuing.
 */
public final class SubagentTools {

    /** The name of the delegation tool produced by {@link #delegateTool}. */
    public static final String DELEGATE = "delegate";

    private SubagentTools() {
    }

    /**
     * A {@code delegate} tool that runs the named subagent on the given subgoal.
     * The tool description lists the available subagents so the model can route
     * without a separate catalog in the system prompt.
     *
     * <p>The description and the {@code subagent} enum are snapshotted from the
     * roster at build time, while the handler resolves against the live roster.
     * Build the tool <em>after</em> the roster is finalized; mutating the roster
     * afterwards leaves the advertised schema stale relative to what the handler
     * accepts.
     */
    public static Tool delegateTool(SubagentRoster roster) {
        Objects.requireNonNull(roster, "roster");
        String description = "Delegate a subgoal to a specialized subagent and return its result. "
                + "Available subagents:\n" + roster.catalog();
        return FunctionTool.builder(DELEGATE, description)
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "subagent", Map.of(
                                        "type", "string",
                                        "description", "Name of the subagent to run.",
                                        "enum", roster.names()),
                                "goal", Map.of(
                                        "type", "string",
                                        "description", "The subgoal for the subagent to pursue.")),
                        "required", List.of("subagent", "goal")))
                .handler(invocation -> {
                    String name = invocation.stringArgument("subagent");
                    String goalText = invocation.stringArgument("goal");
                    if (name == null || name.isBlank()) {
                        return ToolResult.error("Missing required argument 'subagent'.");
                    }
                    if (goalText == null || goalText.isBlank()) {
                        return ToolResult.error("Missing required argument 'goal'.");
                    }
                    Subagent subagent = roster.find(name).orElse(null);
                    if (subagent == null) {
                        return ToolResult.error(
                                "Unknown subagent '" + name + "'. Available: " + roster.names());
                    }
                    AgentResult result;
                    try {
                        result = subagent.handle(Goal.of(goalText));
                    } catch (RuntimeException e) {
                        // A delegation must always come back as a tool result, never a
                        // thrown exception — symmetric with Supervisor.fanOut.
                        return ToolResult.error(
                                "Subagent '" + name + "' failed: " + e.getMessage());
                    }
                    if (result.isSuccess()) {
                        return ToolResult.ok(result.output());
                    }
                    return ToolResult.error(
                            "Subagent '" + name + "' did not complete (" + result.stopReason() + "): "
                                    + result.output());
                })
                .build();
    }
}
