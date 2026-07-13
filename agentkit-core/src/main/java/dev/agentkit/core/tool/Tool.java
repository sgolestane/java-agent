package dev.agentkit.core.tool;

import java.util.Map;

/**
 * A capability the agent can invoke.
 *
 * <p>A tool declares a {@link #name()}, a {@link #description()} the model uses
 * for selection, and an {@link #inputSchema() input schema}; when invoked it
 * receives the parsed {@link ToolInvocation} and returns a {@link ToolResult}.
 *
 * <p>Implementations should be side-effect-aware: hard-to-reverse actions are
 * candidates for gating (see the verification subsystem). {@link #execute} may
 * throw to signal an unexpected failure; the agent loop converts thrown
 * exceptions into an error {@link ToolResult} so a single tool failure does not
 * abort the run.
 */
public interface Tool {

    /** The unique name the model uses to reference this tool. */
    String name();

    /** A natural-language description used by the model for tool selection. */
    String description();

    /** The JSON-Schema-style description of this tool's arguments. */
    Map<String, Object> inputSchema();

    /** Executes the tool for the given invocation. */
    ToolResult execute(ToolInvocation invocation);

    /** The public specification (metadata) advertised to the model. */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema());
    }
}
