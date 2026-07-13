package dev.agentkit.core.context;

import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.List;

/**
 * Estimates the token footprint of text and messages, so context strategies can
 * decide when to compact or edit before the real context window fills.
 *
 * <p>The core ships a fast, provider-agnostic heuristic. For exact accounting an
 * adapter can implement this over a provider's token-counting endpoint; strategies
 * depend only on this interface.
 */
public interface TokenEstimator {

    /** A cheap heuristic estimator (~4 characters per token). */
    TokenEstimator HEURISTIC = new HeuristicTokenEstimator();

    /** Estimates the token count of a text string. */
    int estimate(String text);

    /** Estimates the token count of a message, including per-block overhead. */
    default int estimate(Message message) {
        int total = 4; // role + framing overhead
        for (ContentBlock block : message.content()) {
            total += estimateBlock(block);
        }
        return total;
    }

    /** Estimates the token count of a whole message list. */
    default int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    private int estimateBlock(ContentBlock block) {
        return switch (block) {
            case TextBlock t -> estimate(t.text());
            case ThinkingBlock t -> estimate(t.thinking());
            case ToolResultBlock r -> estimate(r.content()) + 4;
            case ToolUseBlock u -> estimate(u.name()) + estimate(String.valueOf(u.input())) + 6;
        };
    }
}
