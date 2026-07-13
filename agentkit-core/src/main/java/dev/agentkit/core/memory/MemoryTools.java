package dev.agentkit.core.memory;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tools that expose {@link MemoryStore durable memory} and
 * {@link WorkingMemory in-session notes} to the agent.
 */
public final class MemoryTools {

    public static final String MEMORY = "memory";
    public static final String REMEMBER = "remember";
    public static final String RECALL = "recall";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MemoryTools.class);

    private MemoryTools() {
    }

    /**
     * A single {@code memory} tool over a durable {@link MemoryStore}, supporting
     * the commands {@code read}, {@code write}, {@code append}, {@code delete}, and
     * {@code list}.
     */
    public static Tool memoryTool(MemoryStore store) {
        Objects.requireNonNull(store, "store");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string",
                                "enum", List.of("read", "write", "append", "delete", "list"),
                                "description", "The memory operation to perform"),
                        "path", Map.of("type", "string",
                                "description", "Memory key, e.g. 'facts/user.md' (a prefix for 'list')"),
                        "content", Map.of("type", "string",
                                "description", "Text to store (for 'write' and 'append')")),
                "required", List.of("command"));
        return FunctionTool.builder(MEMORY,
                        "Durable memory that persists across sessions. Commands: read/write/append/"
                                + "delete a key, or list keys under a path prefix. Read prior knowledge, "
                                + "and write facts you'll need in future runs. Never store secrets.")
                .schema(schema)
                .handler(inv -> dispatch(store, inv))
                .build();
    }

    /** A {@code remember} tool that appends a note to {@code workingMemory}. */
    public static Tool rememberTool(WorkingMemory workingMemory) {
        Objects.requireNonNull(workingMemory, "workingMemory");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("note", Map.of("type", "string",
                        "description", "A short fact or decision to keep for the rest of this task")),
                "required", List.of("note"));
        return FunctionTool.builder(REMEMBER,
                        "Record a short note in working memory for the rest of this task.")
                .schema(schema)
                .handler(inv -> {
                    String note = inv.stringArgument("note");
                    if (note == null || note.isBlank()) {
                        return ToolResult.error("The 'note' argument is required.");
                    }
                    workingMemory.note(note);
                    return ToolResult.ok("Noted.");
                })
                .build();
    }

    /** A {@code recall} tool that returns the current working-memory notes. */
    public static Tool recallTool(WorkingMemory workingMemory) {
        Objects.requireNonNull(workingMemory, "workingMemory");
        return FunctionTool.builder(RECALL, "List the notes recorded in working memory for this task.")
                .handler(inv -> workingMemory.isEmpty()
                        ? ToolResult.ok("No notes yet.")
                        : ToolResult.ok(workingMemory.render()))
                .build();
    }

    private static ToolResult dispatch(MemoryStore store, ToolInvocation inv) {
        String command = inv.stringArgument("command");
        if (command == null) {
            return ToolResult.error("The 'command' argument is required.");
        }
        String path = inv.stringArgument("path");
        String content = inv.stringArgument("content");
        try {
            return switch (command.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "read" -> requirePath(path, p -> store.read(p)
                        .map(ToolResult::ok)
                        .orElseGet(() -> ToolResult.error("No memory at '" + p + "'.")));
                case "write" -> requirePathAndContent(path, content, (p, c) -> {
                    store.write(p, c);
                    return ToolResult.ok("Wrote memory '" + p + "'.");
                });
                case "append" -> requirePathAndContent(path, content, (p, c) -> {
                    store.append(p, c);
                    return ToolResult.ok("Appended to memory '" + p + "'.");
                });
                case "delete" -> requirePath(path, p ->
                        ToolResult.ok(store.delete(p) ? "Deleted '" + p + "'." : "Nothing to delete at '" + p + "'."));
                case "list" -> {
                    List<String> keys = store.list(path == null ? "" : path);
                    yield ToolResult.ok(keys.isEmpty() ? "Memory is empty." : String.join("\n", keys));
                }
                default -> ToolResult.error("Unknown command '" + command + "'.");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Memory command '{}' failed", command, e);
            return ToolResult.error("Memory operation failed.");
        }
    }

    private interface PathFn {
        ToolResult apply(String path);
    }

    private interface PathContentFn {
        ToolResult apply(String path, String content);
    }

    private static ToolResult requirePath(String path, PathFn fn) {
        if (path == null || path.isBlank()) {
            return ToolResult.error("The 'path' argument is required for this command.");
        }
        return fn.apply(path);
    }

    private static ToolResult requirePathAndContent(String path, String content, PathContentFn fn) {
        if (path == null || path.isBlank()) {
            return ToolResult.error("The 'path' argument is required for this command.");
        }
        if (content == null) {
            return ToolResult.error("The 'content' argument is required for this command.");
        }
        return fn.apply(path, content);
    }
}
