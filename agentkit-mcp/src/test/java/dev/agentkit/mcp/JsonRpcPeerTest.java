package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class JsonRpcPeerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonRpcPeer peer(String scriptedResponses, StringWriter out) {
        return new JsonRpcPeer(new BufferedReader(new StringReader(scriptedResponses)), out, MAPPER);
    }

    @Test
    void requestWritesTheEnvelopeAndReturnsTheMatchingResult() throws Exception {
        StringWriter out = new StringWriter();
        JsonRpcPeer peer = peer("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n", out);

        ObjectNode params = MAPPER.createObjectNode();
        params.put("q", "hi");
        JsonNode result = peer.request("ping", params);

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode sent = MAPPER.readTree(out.toString().trim());
        assertThat(sent.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(sent.path("id").asLong()).isEqualTo(1);
        assertThat(sent.path("method").asText()).isEqualTo("ping");
        assertThat(sent.path("params").path("q").asText()).isEqualTo("hi");
    }

    @Test
    void requestSkipsNotificationsAndUnrelatedResponses() {
        // A server log notification (no id) and a stale response (id 99) precede ours.
        String script = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"method\":\"log\",\"params\":{\"m\":\"warming up\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"stale\":true}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":42}}") + "\n";

        JsonNode result = peer(script, new StringWriter()).request("compute", null);

        assertThat(result.path("value").asInt()).isEqualTo(42);
    }

    @Test
    void anErrorResponseBecomesAnMcpException() {
        String script = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}\n";

        assertThatThrownBy(() -> peer(script, new StringWriter()).request("nope", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("-32601")
                .hasMessageContaining("Method not found");
    }

    @Test
    void aClosedConnectionBecomesAnMcpException() {
        assertThatThrownBy(() -> peer("", new StringWriter()).request("ping", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void notifyWritesAMessageWithNoIdAndConsumesNoResponse() throws Exception {
        StringWriter out = new StringWriter();
        JsonRpcPeer peer = peer("", out); // no response needed

        peer.notify("notifications/initialized", null);

        JsonNode sent = MAPPER.readTree(out.toString().trim());
        assertThat(sent.path("method").asText()).isEqualTo("notifications/initialized");
        assertThat(sent.has("id")).isFalse();
    }

    @Test
    void requestIdsIncrementPerCall() throws Exception {
        StringWriter out = new StringWriter();
        JsonRpcPeer peer = peer(String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}") + "\n", out);

        peer.request("a", null);
        peer.request("b", null);

        String[] lines = out.toString().trim().split("\n");
        assertThat(MAPPER.readTree(lines[0]).path("id").asLong()).isEqualTo(1);
        assertThat(MAPPER.readTree(lines[1]).path("id").asLong()).isEqualTo(2);
    }
}
