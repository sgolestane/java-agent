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
 * notifications or server-initiated requests.
 *
 * <p>This is a client for a cooperative single-server session; it does not answer
 * server-initiated requests (e.g. a server {@code ping}) — it simply ignores them.
 * {@code request} and {@code notify} are synchronized so an accidental shared use
 * across threads serializes rather than corrupting the framing; there is still only
 * one outstanding request at a time. {@code awaitResponse} blocks with no timeout,
 * so a hung-but-alive server blocks the caller until the connection closes.
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
    synchronized JsonNode request(String method, JsonNode params) {
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
    synchronized void notify(String method, JsonNode params) {
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
                // A message carrying "method" is a server notification or a
                // server-initiated request (e.g. ping), never a response — skip it,
                // even if its id happens to collide with ours.
                if (message.has("method")) {
                    continue;
                }
                JsonNode idNode = message.get("id");
                // Skip responses to other (already-consumed) requests.
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

    /** Best-effort close of both streams, releasing their file descriptors. */
    synchronized void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
