package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@code web_search} tool handler (no network, no model). */
class WebResearchToolsTest {

    private static ToolResult run(WebSearch search, Map<String, Object> args) {
        FunctionTool tool = WebResearchTools.webSearchTool(search);
        return tool.execute(new ToolInvocation("t1", "web_search", args));
    }

    @Test
    void rendersResultsAsANumberedCitableList() {
        WebSearch search = (query, max) -> List.of(
                new WebSearch.Result("Add member", "https://learn.microsoft.com/graph/api/group-post-members",
                        "POST /groups/{id}/members/$ref"));

        ToolResult result = run(search, Map.of("query", "graph add member"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content())
                .contains("1. Add member")
                .contains("https://learn.microsoft.com/graph/api/group-post-members")
                .contains("members/$ref");
    }

    @Test
    void clampsMaxResultsAndPassesTheQueryThrough() {
        int[] seenMax = {-1};
        String[] seenQuery = {null};
        WebSearch search = (query, max) -> {
            seenQuery[0] = query;
            seenMax[0] = max;
            return List.of();
        };

        run(search, Map.of("query", "  spaced  ", "max_results", 99));

        assertThat(seenQuery[0]).isEqualTo("spaced");   // stripped
        assertThat(seenMax[0]).isEqualTo(10);           // clamped to the 1..10 range
    }

    @Test
    void reportsAnEmptyResultSetWithoutErroring() {
        ToolResult result = run((query, max) -> List.of(), Map.of("query", "nothing matches"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("No results found for: nothing matches");
    }

    @Test
    void missingQueryIsAnError() {
        ToolResult result = run((query, max) -> List.of(), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("query");
    }

    @Test
    void backendFailureBecomesAToolError() {
        WebSearch search = (query, max) -> {
            throw new IllegalStateException("provider down");
        };

        ToolResult result = run(search, Map.of("query", "anything"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("web_search failed").contains("provider down");
    }
}
