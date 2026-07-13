package dev.agentkit.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The public declaration of a {@link Tool}: the metadata sent to the model so it
 * can decide when and how to call the tool.
 *
 * @param name        unique tool name; never {@code null} or blank
 * @param description natural-language description used by the model for tool
 *                    selection; never {@code null}
 * @param inputSchema JSON-Schema-style description of the tool's arguments
 *                    (typically {@code {"type":"object","properties":{...},
 *                    "required":[...]}}); never {@code null}. Stored as a
 *                    defensive, unmodifiable copy.
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }

    /** A schema for a tool that takes no arguments. */
    public static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
}
