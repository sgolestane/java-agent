package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class McpToolsTest {

    /** A fake connection scripted with a tool list and a call handler. */
    private static final class FakeConnection implements McpConnection {
        private final List<McpToolInfo> tools;
        private final BiFunction<String, Map<String, Object>, McpCallResult> onCall;

        FakeConnection(List<McpToolInfo> tools,
                       BiFunction<String, Map<String, Object>, McpCallResult> onCall) {
            this.tools = tools;
            this.onCall = onCall;
        }

        @Override
        public List<McpToolInfo> listTools() {
            return tools;
        }

        @Override
        public McpCallResult callTool(String name, Map<String, Object> arguments) {
            return onCall.apply(name, arguments);
        }

        @Override
        public void close() {
        }
    }

    private static final McpToolInfo ECHO = new McpToolInfo("echo", "echoes input",
            Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))));

    @Test
    void loadWrapsEachServerToolWithItsSpec() {
        McpConnection connection = new FakeConnection(List.of(ECHO), (n, a) -> new McpCallResult("", false));

        List<Tool> tools = McpTools.load(connection);

        assertThat(tools).hasSize(1);
        Tool tool = tools.get(0);
        assertThat(tool.name()).isEqualTo("echo");
        assertThat(tool.description()).isEqualTo("echoes input");
        assertThat(tool.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void executeForwardsArgumentsAndReturnsAnOkResult() {
        Map<String, Object>[] captured = new Map[1];
        McpConnection connection = new FakeConnection(List.of(ECHO), (name, args) -> {
            captured[0] = args;
            return new McpCallResult("you said: " + args.get("msg"), false);
        });

        Tool echo = McpTools.load(connection).get(0);
        ToolResult result = echo.execute(new ToolInvocation("t1", "echo", Map.of("msg", "hi")));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("you said: hi");
        assertThat(captured[0]).containsEntry("msg", "hi");
    }

    @Test
    void aServerFlaggedErrorBecomesAnErrorToolResult() {
        McpConnection connection = new FakeConnection(List.of(ECHO),
                (n, a) -> new McpCallResult("boom", true));

        ToolResult result = McpTools.load(connection).get(0)
                .execute(new ToolInvocation("t1", "echo", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("boom");
    }

    @Test
    void aTransportFailureBecomesAnErrorResultNotAThrow() {
        McpConnection connection = new FakeConnection(List.of(ECHO), (n, a) -> {
            throw new McpException("pipe broke");
        });

        ToolResult result = McpTools.load(connection).get(0)
                .execute(new ToolInvocation("t1", "echo", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("echo").contains("pipe broke");
    }

    @Test
    void registryAdvertisesTheServerTools() {
        McpConnection connection = new FakeConnection(List.of(ECHO), (n, a) -> new McpCallResult("", false));

        ToolRegistry registry = McpTools.registry(connection);

        assertThat(registry.advertisedSpecs()).singleElement()
                .satisfies(spec -> assertThat(spec.name()).isEqualTo("echo"));
        assertThat(registry.find("echo")).isPresent();
    }
}
