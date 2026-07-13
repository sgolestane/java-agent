package dev.agentkit.core.message;

/**
 * A single block of content within a {@link Message}.
 *
 * <p>Modelled as a sealed hierarchy so callers can exhaustively pattern-match
 * over the concrete block types. This mirrors the content-block model used by
 * modern tool-calling LLM APIs while remaining provider-agnostic.
 */
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock {
}
