package dev.agentkit.examples;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a client-executed {@code web_search} tool over a {@link WebSearch}
 * backend.
 *
 * <p>This is a <em>client-side</em> tool — the agent loop calls the handler,
 * which runs the search and returns the results as text. That is the portable
 * way to give AgentKit web access: it works against any LLM backend, including
 * Claude on Amazon Bedrock, where Anthropic's server-side web-search tool is not
 * available.
 */
public final class WebResearchTools {

    private WebResearchTools() {
    }

    /** A {@code web_search} {@link FunctionTool} backed by {@code search}. */
    public static FunctionTool webSearchTool(WebSearch search) {
        Objects.requireNonNull(search, "search");
        return FunctionTool.builder("web_search",
                        "Search the web for current information. Returns a ranked list of results, "
                                + "each with a title, URL, and short snippet. Call this when the answer "
                                + "depends on up-to-date documentation or facts you are unsure of, then "
                                + "ground your answer in the results and cite the source URLs.")
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "The search query."),
                                "max_results", Map.of("type", "integer",
                                        "description", "Maximum results to return (1-10, default 5).")),
                        "required", List.of("query")))
                .handler(inv -> {
                    String query = inv.stringArgument("query");
                    if (query == null || query.isBlank()) {
                        return ToolResult.error("The 'query' argument is required.");
                    }
                    int maxResults = clampMaxResults(inv.argument("max_results"));
                    try {
                        return ToolResult.ok(render(query.strip(), search.search(query.strip(), maxResults)));
                    } catch (RuntimeException e) {
                        return ToolResult.error("web_search failed: " + e.getMessage());
                    }
                })
                .build();
    }

    /** Renders results as a numbered list the model can read and cite. */
    static String render(String query, List<WebSearch.Result> results) {
        if (results.isEmpty()) {
            return "No results found for: " + query;
        }
        StringBuilder sb = new StringBuilder("Search results for: ").append(query).append('\n');
        int i = 1;
        for (WebSearch.Result r : results) {
            sb.append('\n').append(i++).append(". ").append(r.title()).append('\n')
                    .append("   ").append(r.url()).append('\n')
                    .append("   ").append(r.snippet()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Coerces the model-supplied {@code max_results} into [1, 10], defaulting to 5. */
    private static int clampMaxResults(Object value) {
        if (value instanceof Number n) {
            return Math.max(1, Math.min(10, n.intValue()));
        }
        return 5;
    }
}
