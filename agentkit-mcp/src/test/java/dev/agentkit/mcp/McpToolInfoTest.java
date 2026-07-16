package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolInfoTest {

    @Test
    void nullDescriptionAndSchemaDefaultToEmpty() {
        McpToolInfo info = new McpToolInfo("t", null, null);
        assertThat(info.description()).isEmpty();
        assertThat(info.inputSchema()).isEmpty();
    }

    @Test
    void aSchemaWithANullValuedEntryIsToleratedNotRejected() {
        // Map.copyOf would throw on a null value; a server schema may carry one.
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("default", null);

        McpToolInfo info = new McpToolInfo("t", "d", schema);

        assertThat(info.inputSchema()).containsEntry("type", "object");
        assertThat(info.inputSchema()).containsKey("default");
        assertThat(info.inputSchema().get("default")).isNull();
    }

    @Test
    void theSchemaCopyIsDefensiveAndUnmodifiable() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        McpToolInfo info = new McpToolInfo("t", "d", schema);

        schema.put("type", "mutated"); // must not affect the stored copy
        assertThat(info.inputSchema()).containsEntry("type", "object");
    }
}
