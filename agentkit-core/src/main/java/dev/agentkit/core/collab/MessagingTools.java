package dev.agentkit.core.collab;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives agents a {@code send_message} tool for bidirectional, peer-to-peer
 * collaboration: an agent messages a named peer, that peer runs to completion on
 * the message, and its reply comes back as the tool result. Unlike a supervisor's
 * top-down {@code delegate}, every collaborating agent can hold this tool — so a
 * peer may message a third peer while answering, forming a conversation rather
 * than a one-way dispatch.
 *
 * <p>The peers are a {@link PeerGroup}. Because a reply can trigger further
 * messages, a shared {@link #budget(int) message budget} bounds the total number
 * of messages across the whole group and guarantees termination: once it is
 * exhausted, {@code send_message} returns an error instead of running another
 * peer. Build every peer's tool from the <em>same</em> budget so the cap is
 * global.
 */
public final class MessagingTools {

    /** The name of the tool produced by {@link #sendMessageTool}. */
    public static final String SEND_MESSAGE = "send_message";

    private MessagingTools() {
    }

    /** A fresh shared budget of {@code maxMessages} total messages. */
    public static AtomicInteger budget(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0");
        }
        return new AtomicInteger(maxMessages);
    }

    /**
     * A {@code send_message} tool over {@code peers}, drawing from the shared
     * {@code budget}. Peers list themselves in the tool description so the model
     * can address without a separate roster in the prompt.
     */
    public static Tool sendMessageTool(PeerGroup peers, AtomicInteger budget) {
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(budget, "budget");
        String description = "Send a message to another agent and get its reply. Use this to ask a "
                + "peer for help, information, or a second opinion. Available agents:\n" + peers.catalog();
        return FunctionTool.builder(SEND_MESSAGE, description)
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "to", Map.of(
                                        "type", "string",
                                        "description", "Name of the agent to message.",
                                        "enum", peers.names()),
                                "message", Map.of(
                                        "type", "string",
                                        "description", "What to say or ask.")),
                        "required", List.of("to", "message")))
                .handler(inv -> {
                    String to = inv.stringArgument("to");
                    String message = inv.stringArgument("message");
                    if (to == null || to.isBlank()) {
                        return ToolResult.error("Missing required argument 'to'.");
                    }
                    if (message == null || message.isBlank()) {
                        return ToolResult.error("Missing required argument 'message'.");
                    }
                    Peer peer = peers.find(to).orElse(null);
                    if (peer == null) {
                        return ToolResult.error("Unknown agent '" + to + "'. Available: " + peers.names());
                    }
                    // Reserve a message before running the peer so a reply that sends more
                    // messages can never exceed the global cap (guarantees termination).
                    if (budget.getAndDecrement() <= 0) {
                        budget.incrementAndGet(); // undo; nothing was sent
                        return ToolResult.error(
                                "Message budget exhausted; cannot send more messages this run.");
                    }
                    AgentResult reply;
                    try {
                        reply = peer.handle(Goal.of(message.strip()));
                    } catch (RuntimeException e) {
                        // A message must always come back as a tool result, never a throw.
                        return ToolResult.error("Agent '" + to + "' failed: " + e.getMessage());
                    }
                    if (reply.isSuccess()) {
                        return ToolResult.ok(reply.output());
                    }
                    return ToolResult.error("Agent '" + to + "' did not complete ("
                            + reply.stopReason() + "): " + reply.output());
                })
                .build();
    }
}
