package dev.agentkit.core.tool;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link Tool} backed by a plain function, for defining tools inline without a
 * dedicated class.
 *
 * <pre>{@code
 * Tool echo = FunctionTool.builder("echo", "Echoes its input")
 *         .schema(Map.of("type", "object",
 *                        "properties", Map.of("text", Map.of("type", "string")),
 *                        "required", List.of("text")))
 *         .handler(inv -> ToolResult.ok(inv.stringArgument("text")))
 *         .build();
 * }</pre>
 */
public final class FunctionTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Function<ToolInvocation, ToolResult> handler;

    private FunctionTool(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.inputSchema = builder.inputSchema;
        this.handler = builder.handler;
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return handler.apply(invocation);
    }

    /** Builder for {@link FunctionTool}. */
    public static final class Builder {
        private final String name;
        private final String description;
        private Map<String, Object> inputSchema = ToolSpec.emptyObjectSchema();
        private Function<ToolInvocation, ToolResult> handler;

        private Builder(String name, String description) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
        }

        public Builder schema(Map<String, Object> inputSchema) {
            this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
            return this;
        }

        public Builder handler(Function<ToolInvocation, ToolResult> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public FunctionTool build() {
            Objects.requireNonNull(handler, "handler must be set");
            return new FunctionTool(this);
        }
    }
}
