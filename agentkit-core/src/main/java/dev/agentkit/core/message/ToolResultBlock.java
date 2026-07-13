package dev.agentkit.core.message;

import java.util.Objects;

/**
 * The result of a tool invocation, sent back to the model.
 *
 * @param toolUseId the {@link ToolUseBlock#id()} this result corresponds to;
 *                  never {@code null}
 * @param content   the textual result content; never {@code null}
 * @param isError   whether the tool invocation failed. When {@code true}, the
 *                  model is expected to treat {@code content} as an error
 *                  message and adapt.
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContentBlock {

    public ToolResultBlock {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(content, "content");
    }

    public static ToolResultBlock ok(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, false);
    }

    public static ToolResultBlock error(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, true);
    }
}
