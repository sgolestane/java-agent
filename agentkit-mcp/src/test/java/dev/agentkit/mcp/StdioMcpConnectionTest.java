package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StdioMcpConnectionTest {

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void completesTheHandshakeThenListsAndCallsTools() {
        // Scripted server responses in id order: initialize(1), tools/list(2), tools/call(3).
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"serverInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\","
                        + "\"description\":\"echoes\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"hello\"}],\"isError\":false}}");
        StringWriter out = new StringWriter();
        StdioMcpConnection connection = new StdioMcpConnection(new StringReader(script), out, () -> { });

        List<McpToolInfo> tools = connection.listTools();
        assertThat(tools).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("echo");
            assertThat(t.description()).isEqualTo("echoes");
            assertThat(t.inputSchema()).containsEntry("type", "object");
        });

        McpCallResult result = connection.callTool("echo", Map.of("msg", "hi"));
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.isError()).isFalse();

        // The client sent initialize, the initialized notification, then the two calls.
        String sent = out.toString();
        assertThat(sent).contains("\"method\":\"initialize\"")
                .contains("\"method\":\"notifications/initialized\"")
                .contains("\"method\":\"tools/list\"")
                .contains("\"method\":\"tools/call\"")
                .contains("\"name\":\"echo\"");
    }

    @Test
    void callToolFlattensTextBlocksAndHonoursIsError() {
        // initialize(1) then tools/call(2) returning mixed content with isError=true.
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"a\"},"
                        + "{\"type\":\"image\",\"data\":\"...\"},"
                        + "{\"type\":\"text\",\"text\":\"b\"}],\"isError\":true}}");
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> { });

        McpCallResult result = connection.callTool("t", Map.of());

        assertThat(result.text()).isEqualTo("ab"); // text blocks concatenated, image ignored
        assertThat(result.isError()).isTrue();
    }

    @Test
    void closeInvokesTheTransportCloser() {
        String script = lines("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        AtomicBoolean closed = new AtomicBoolean(false);
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> closed.set(true));

        connection.close();

        assertThat(closed).isTrue();
    }
}
