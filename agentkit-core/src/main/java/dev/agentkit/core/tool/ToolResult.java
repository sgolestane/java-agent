package dev.agentkit.core.tool;

import java.util.Objects;

/**
 * The outcome of executing a {@link Tool}.
 *
 * @param content textual result returned to the model; never {@code null}
 * @param isError whether the invocation failed; when {@code true} the model
 *                should treat {@code content} as an error message
 */
public record ToolResult(String content, boolean isError) {

    public ToolResult {
        Objects.requireNonNull(content, "content");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
