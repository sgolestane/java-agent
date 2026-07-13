package dev.agentkit.core.knowledge;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes a {@link KnowledgeBase} to the agent as a {@code knowledge_search} tool.
 */
public final class KnowledgeTools {

    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_CAP = 25;
    /** Per-passage character budget, so a large custom chunk cannot flood the context. */
    private static final int MAX_PASSAGE_CHARS = 1000;

    private KnowledgeTools() {
    }

    /** A {@code knowledge_search} tool over {@code knowledgeBase}. */
    public static Tool knowledgeSearchTool(KnowledgeBase knowledgeBase) {
        Objects.requireNonNull(knowledgeBase, "knowledgeBase");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string",
                                "description", "The information need to search for"),
                        "max_results", Map.of("type", "integer",
                                "description", "Maximum number of results",
                                "default", DEFAULT_MAX_RESULTS,
                                "minimum", 1,
                                "maximum", MAX_RESULTS_CAP)),
                "required", List.of("query"));
        return FunctionTool.builder(KNOWLEDGE_SEARCH,
                        "Search the knowledge base for information relevant to a query and return "
                                + "the most relevant passages with their source ids.")
                .schema(schema)
                .handler(inv -> search(knowledgeBase, inv))
                .build();
    }

    private static ToolResult search(KnowledgeBase kb, ToolInvocation inv) {
        String query = inv.stringArgument("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("The 'query' argument is required.");
        }
        int maxResults = clampMaxResults(inv.argument("max_results"));
        List<SearchResult> results = kb.search(query, maxResults);
        if (results.isEmpty()) {
            return ToolResult.ok("No results found for \"" + query + "\".");
        }
        StringBuilder sb = new StringBuilder("Found ").append(results.size()).append(" passage(s):\n");
        for (SearchResult result : results) {
            sb.append('\n').append(citation(result)).append('\n')
                    .append(truncate(result.chunk().text().strip())).append('\n');
        }
        return ToolResult.ok(sb.toString().stripTrailing());
    }

    private static String citation(SearchResult result) {
        Map<String, Object> meta = result.chunk().metadata();
        StringBuilder cite = new StringBuilder("[").append(result.chunk().id()).append("]");
        Object title = meta.get("title");
        Object url = meta.get("url");
        if (title != null) {
            cite.append(" \"").append(title).append('"');
        }
        if (url != null) {
            cite.append(" <").append(url).append('>');
        }
        cite.append(" (score ").append(String.format(java.util.Locale.ROOT, "%.3f", result.score())).append(')');
        return cite.toString();
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_PASSAGE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PASSAGE_CHARS) + "…";
    }

    private static int clampMaxResults(Object raw) {
        int requested = DEFAULT_MAX_RESULTS;
        if (raw instanceof Number n) {
            requested = n.intValue();
        } else if (raw instanceof String s) {
            try {
                requested = Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                requested = DEFAULT_MAX_RESULTS;
            }
        }
        if (requested < 1) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(requested, MAX_RESULTS_CAP);
    }
}
