package dev.agentkit.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool advertised by an MCP server, as returned by {@code tools/list}.
 *
 * @param name        the tool's unique name
 * @param description a natural-language description for model selection (may be empty)
 * @param inputSchema the JSON-Schema description of the tool's arguments
 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {

    public McpToolInfo {
        java.util.Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        // A defensive, unmodifiable copy that tolerates null values — a schema from an
        // untrusted server may legitimately carry them (e.g. "default": null), and
        // Map.copyOf would reject those.
        inputSchema = inputSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
}
