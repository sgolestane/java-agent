package dev.agentkit.core.tool;

import dev.agentkit.core.retrieval.Bm25Index;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link ToolRegistry} that implements <em>progressive tool disclosure</em>.
 *
 * <p>Tools are registered as either <em>always-available</em> (advertised to the
 * model from the first turn) or <em>deferred</em> (hidden until discovered). A
 * synthetic {@code search_tools} tool is advertised whenever any tool remains
 * unrevealed; when the model calls it, the best-matching tools are revealed and
 * become available on subsequent turns. This keeps the fixed context small while
 * supporting large tool libraries, and — because tool specs are only ever
 * <em>added</em>, never removed — it preserves the provider prompt cache.
 *
 * <p><strong>State:</strong> the set of revealed tools is mutated as a run
 * progresses, so a registry instance is <em>per-run</em>. Build a fresh registry
 * for each agent run (construction is cheap; the BM25 index is built once here).
 * It is not thread-safe.
 */
public final class DisclosingToolRegistry implements ToolRegistry {

    /** Default name of the synthetic search tool. */
    public static final String DEFAULT_SEARCH_TOOL_NAME = "search_tools";

    private final Map<String, Tool> all;
    private final Set<String> revealed;
    private final Bm25Index index;
    private final int searchLimit;
    private final Tool searchTool;

    private DisclosingToolRegistry(Builder b) {
        this.all = new LinkedHashMap<>(b.all);
        this.revealed = new LinkedHashSet<>(b.alwaysAvailable);
        this.searchLimit = b.searchLimit;

        Map<String, String> corpus = new LinkedHashMap<>();
        this.all.forEach((name, tool) -> corpus.put(name, name + " " + tool.description()));
        this.index = Bm25Index.of(corpus);

        this.searchTool = buildSearchTool(b.searchToolName);
        if (all.containsKey(b.searchToolName)) {
            throw new IllegalArgumentException(
                    "A registered tool collides with the search tool name '" + b.searchToolName + "'");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<Tool> find(String name) {
        if (searchTool.name().equals(name)) {
            return Optional.of(searchTool);
        }
        return Optional.ofNullable(all.get(name));
    }

    @Override
    public List<Tool> tools() {
        return List.copyOf(all.values());
    }

    @Override
    public List<ToolSpec> advertisedSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool tool : all.values()) {
            if (revealed.contains(tool.name())) {
                specs.add(tool.spec());
            }
        }
        if (hasUnrevealed()) {
            specs.add(searchTool.spec());
        }
        return List.copyOf(specs);
    }

    /** The names currently revealed to the model. */
    public Set<String> revealedNames() {
        return Set.copyOf(revealed);
    }

    private boolean hasUnrevealed() {
        return revealed.size() < all.size();
    }

    private Tool buildSearchTool(String name) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "Keywords describing the capability you need")),
                "required", List.of("query"));
        return FunctionTool.builder(name,
                        "Search the tool library for tools matching a capability, and make them "
                                + "available to call. Use this when no advertised tool fits the task.")
                .schema(schema)
                .handler(this::handleSearch)
                .build();
    }

    private ToolResult handleSearch(ToolInvocation invocation) {
        String query = invocation.stringArgument("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("The 'query' argument is required and must be non-blank.");
        }
        List<Bm25Index.Scored> hits = index.search(query, searchLimit);
        if (hits.isEmpty()) {
            return ToolResult.ok("No tools matched \"" + query + "\". Try different keywords.");
        }
        StringBuilder sb = new StringBuilder("Revealed ").append(hits.size())
                .append(" tool(s) — you can now call them:\n");
        for (Bm25Index.Scored hit : hits) {
            revealed.add(hit.id());
            Tool tool = all.get(hit.id());
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return ToolResult.ok(sb.toString().stripTrailing());
    }

    /** Builder for {@link DisclosingToolRegistry}. */
    public static final class Builder {
        private final Map<String, Tool> all = new LinkedHashMap<>();
        private final Set<String> alwaysAvailable = new LinkedHashSet<>();
        private int searchLimit = 5;
        private String searchToolName = DEFAULT_SEARCH_TOOL_NAME;

        private Builder() {
        }

        /** Registers a tool that is advertised to the model from the first turn. */
        public Builder alwaysAvailable(Tool tool) {
            register(tool);
            alwaysAvailable.add(tool.name());
            return this;
        }

        /** Registers a tool that is hidden until revealed by a search. */
        public Builder deferred(Tool tool) {
            register(tool);
            return this;
        }

        public Builder searchLimit(int searchLimit) {
            if (searchLimit <= 0) {
                throw new IllegalArgumentException("searchLimit must be > 0");
            }
            this.searchLimit = searchLimit;
            return this;
        }

        public Builder searchToolName(String searchToolName) {
            this.searchToolName = Objects.requireNonNull(searchToolName, "searchToolName");
            return this;
        }

        private void register(Tool tool) {
            Objects.requireNonNull(tool, "tool");
            if (all.containsKey(tool.name())) {
                throw new IllegalArgumentException("A tool named '" + tool.name() + "' is already registered");
            }
            all.put(tool.name(), tool);
        }

        public DisclosingToolRegistry build() {
            return new DisclosingToolRegistry(this);
        }
    }
}
