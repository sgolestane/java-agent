package dev.agentkit.mcp;

import java.util.List;
import java.util.Map;

/**
 * A live connection to a Model Context Protocol (MCP) server, reduced to the two
 * operations AgentKit needs: discovering tools and invoking them.
 *
 * <p>This is the seam the tool bridge ({@link McpTools}) depends on, so the bridge
 * is testable against a fake connection and independent of transport. The shipped
 * transport is {@link StdioMcpConnection} (JSON-RPC over a subprocess's stdio).
 * Implementations are not required to be thread-safe.
 */
public interface McpConnection extends AutoCloseable {

    /** Lists the tools the server advertises ({@code tools/list}). */
    List<McpToolInfo> listTools();

    /**
     * Invokes a tool by name with the given arguments ({@code tools/call}).
     *
     * @param name      the tool name
     * @param arguments the tool arguments (may be empty)
     * @return the tool's result, including the server's error flag
     * @throws McpException if the transport fails or the server returns a protocol error
     */
    McpCallResult callTool(String name, Map<String, Object> arguments);

    /** Closes the connection and releases its transport (e.g. terminates the subprocess). */
    @Override
    void close();
}
