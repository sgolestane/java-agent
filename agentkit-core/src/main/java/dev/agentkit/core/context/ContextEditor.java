package dev.agentkit.core.context;

import dev.agentkit.core.message.Message;
import java.util.List;

/**
 * Prunes stale content from a conversation history <em>in place of</em>
 * summarising it (contrast {@link Compactor}). Editing removes bulk — typically
 * old tool results — while preserving the message/tool-call structure, so the
 * transcript stays valid and cheap without a model call.
 */
@FunctionalInterface
public interface ContextEditor {

    /** An editor that returns the history unchanged. */
    ContextEditor NONE = history -> history;

    /** Returns a possibly-pruned copy of {@code history}. */
    List<Message> edit(List<Message> history);
}
