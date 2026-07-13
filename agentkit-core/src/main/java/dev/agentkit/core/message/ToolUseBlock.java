package dev.agentkit.core.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A request from the model to invoke a tool.
 *
 * @param id    unique identifier for this invocation, echoed back on the
 *              matching {@link ToolResultBlock}; never {@code null}
 * @param name  the name of the tool to invoke; never {@code null}
 * @param input the parsed tool arguments as a JSON-like map; never {@code null}.
 *              A defensive, order-preserving, unmodifiable copy is stored. Null
 *              values are permitted (JSON {@code null} arguments). The copy is
 *              <em>shallow</em>: nested mutable values are shared, so callers
 *              should treat argument values as read-only.
 */
public record ToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock {

    public ToolUseBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(input, "input");
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    public static ToolUseBlock of(String id, String name, Map<String, Object> input) {
        return new ToolUseBlock(id, name, input);
    }
}
