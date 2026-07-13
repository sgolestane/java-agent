package dev.agentkit.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A concrete request to execute a tool, as issued by the model.
 *
 * @param id        the invocation id, echoed back on the result so the model can
 *                  correlate; never {@code null}
 * @param name      the name of the tool to run; never {@code null}
 * @param arguments the parsed arguments; never {@code null}. Stored as a
 *                  defensive, order-preserving, unmodifiable copy (null values
 *                  permitted).
 */
public record ToolInvocation(String id, String name, Map<String, Object> arguments) {

    public ToolInvocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** Returns the argument for {@code key}, or {@code null} if absent. */
    public Object argument(String key) {
        return arguments.get(key);
    }

    /** Returns the argument for {@code key} as a string, or {@code null} if absent. */
    public String stringArgument(String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }
}
