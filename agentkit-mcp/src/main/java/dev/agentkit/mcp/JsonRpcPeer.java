package dev.agentkit.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;

/**
 * A minimal synchronous JSON-RPC 2.0 client speaking the MCP stdio framing:
 * one JSON message per line. It writes a request and blocks reading lines until the
 * response with the matching id arrives, skipping any interleaved server
 * notifications or unrelated messages.
 *
 * <p>This is a client for a cooperative single-server session; it does not answer
 * server-initiated requests. Not thread-safe — one outstanding request at a time.
 */
final class JsonRpcPeer {

    private final BufferedReader in;
    private final Writer out;
    private final ObjectMapper mapper;
    private long nextId = 1;

    JsonRpcPeer(BufferedReader in, Writer out, ObjectMapper mapper) {
        this.in = in;
        this.out = out;
        this.mapper = mapper;
    }

    /**
     * Sends a request and returns its {@code result} node.
     *
     * @throws McpException on transport failure or a JSON-RPC error response
     */
    JsonNode request(String method, JsonNode params) {
        long id = nextId++;
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        writeMessage(request);
        return awaitResponse(id, method);
    }

    /** Sends a notification (a request with no id, expecting no response). */
    void notify(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        writeMessage(notification);
    }

    private JsonNode awaitResponse(long id, String method) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message = mapper.readTree(line);
                JsonNode idNode = message.get("id");
                // Skip notifications (no id) and responses to other requests.
                if (idNode == null || idNode.isNull() || idNode.asLong() != id) {
                    continue;
                }
                if (message.hasNonNull("error")) {
                    JsonNode error = message.get("error");
                    throw new McpException("MCP error " + error.path("code").asInt()
                            + " on " + method + ": " + error.path("message").asText());
                }
                return message.path("result");
            }
            throw new McpException("connection closed while awaiting response to " + method);
        } catch (IOException e) {
            throw new McpException("failed reading response to " + method, e);
        }
    }

    private void writeMessage(JsonNode message) {
        try {
            out.write(mapper.writeValueAsString(message));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new McpException("failed writing JSON-RPC message", e);
        }
    }
}
