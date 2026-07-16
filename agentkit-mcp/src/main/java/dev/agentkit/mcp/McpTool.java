package dev.agentkit.mcp;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts a single MCP server tool to AgentKit's {@link Tool} interface, so a remote
 * MCP tool is invoked by the agent loop exactly like a local one.
 *
 * <p>{@link #execute} forwards the invocation's arguments to the server via the
 * shared {@link McpConnection}. A server-flagged error becomes an error
 * {@link ToolResult}; a transport failure ({@link McpException}) is likewise turned
 * into an error result rather than propagated, so a failing MCP call does not abort
 * the run (matching the agent loop's contract for local tools).
 */
public final class McpTool implements Tool {

    private final McpConnection connection;
    private final McpToolInfo info;

    public McpTool(McpConnection connection, McpToolInfo info) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.info = Objects.requireNonNull(info, "info");
    }

    @Override
    public String name() {
        return info.name();
    }

    @Override
    public String description() {
        return info.description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return info.inputSchema();
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            McpCallResult result = connection.callTool(info.name(), invocation.arguments());
            return new ToolResult(result.text(), result.isError());
        } catch (McpException e) {
            return ToolResult.error("MCP tool '" + info.name() + "' failed: " + e.getMessage());
        }
    }
}
