package dev.agentkit.core.collab;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes a {@link Blackboard} to an agent as {@code post_note} and
 * {@code read_board} tools, so collaborating agents share a workspace.
 *
 * <p>The author is bound per agent when the {@code post_note} tool is built — an
 * agent cannot post as someone else — while every agent reads the same board.
 * Give each collaborating agent its own {@code post_note} tool (its name) and a
 * shared {@code read_board} tool.
 */
public final class BlackboardTools {

    /** The name of the tool produced by {@link #postNoteTool}. */
    public static final String POST_NOTE = "post_note";
    /** The name of the tool produced by {@link #readBoardTool}. */
    public static final String READ_BOARD = "read_board";

    private BlackboardTools() {
    }

    /**
     * A {@code post_note} tool that appends to {@code board} under {@code author}.
     * The author is fixed here, not taken from the model, so posts are reliably
     * attributed.
     */
    public static Tool postNoteTool(Blackboard board, String author) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(author, "author");
        return FunctionTool.builder(POST_NOTE,
                        "Post a note to the shared workspace for other agents to read. "
                                + "Group related notes under the same short 'topic'.")
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "topic", Map.of("type", "string",
                                        "description", "A short label grouping related notes."),
                                "content", Map.of("type", "string",
                                        "description", "The note body.")),
                        "required", List.of("topic", "content")))
                .handler(inv -> {
                    String topic = inv.stringArgument("topic");
                    String content = inv.stringArgument("content");
                    if (topic == null || topic.isBlank()) {
                        return ToolResult.error("The 'topic' argument is required.");
                    }
                    if (content == null || content.isBlank()) {
                        return ToolResult.error("The 'content' argument is required.");
                    }
                    Blackboard.Entry entry = board.post(author, topic.strip(), content.strip());
                    return ToolResult.ok("Posted note #" + entry.id() + " under topic '" + entry.topic() + "'.");
                })
                .build();
    }

    /**
     * A {@code read_board} tool that returns the workspace contents, optionally
     * filtered to a single {@code topic}.
     */
    public static Tool readBoardTool(Blackboard board) {
        Objects.requireNonNull(board, "board");
        return FunctionTool.builder(READ_BOARD,
                        "Read notes other agents have posted to the shared workspace. "
                                + "Pass a 'topic' to read only that topic, or omit it to read everything.")
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "topic", Map.of("type", "string",
                                        "description", "Optional topic filter.")),
                        "required", List.of()))
                .handler(inv -> {
                    String topic = inv.stringArgument("topic");
                    List<Blackboard.Entry> entries = (topic == null || topic.isBlank())
                            ? board.entries()
                            : board.byTopic(topic.strip());
                    return ToolResult.ok(render(topic, entries));
                })
                .build();
    }

    /** Renders entries for the model. */
    static String render(String topic, List<Blackboard.Entry> entries) {
        if (entries.isEmpty()) {
            return (topic == null || topic.isBlank())
                    ? "The shared workspace is empty."
                    : "No notes under topic '" + topic.strip() + "'.";
        }
        StringBuilder sb = new StringBuilder("Shared workspace (" + entries.size() + " note(s)):");
        for (Blackboard.Entry entry : entries) {
            sb.append("\n\n#").append(entry.id())
                    .append(" [").append(entry.topic()).append("] by ").append(entry.author())
                    .append('\n').append(entry.content());
        }
        return sb.toString();
    }
}
