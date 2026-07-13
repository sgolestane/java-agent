package dev.agentkit.core.context;

import dev.agentkit.core.message.Message;
import java.util.List;
import java.util.Objects;

/**
 * Factories for common {@link ContextStrategy} compositions.
 */
public final class ContextStrategies {

    private ContextStrategies() {
    }

    /** The pass-through strategy. */
    public static ContextStrategy identity() {
        return ContextStrategy.IDENTITY;
    }

    /**
     * Applies a {@link ContextEditor} (prune) and then a {@link Compactor}
     * (summarise): editing first reclaims cheap bulk, so compaction runs less
     * often and over smaller input.
     */
    public static ContextStrategy of(ContextEditor editor, Compactor compactor) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(compactor, "compactor");
        return history -> {
            List<Message> edited = editor.edit(history);
            return compactor.compact(edited);
        };
    }

    /** A strategy that only edits (no compaction). */
    public static ContextStrategy editing(ContextEditor editor) {
        return of(editor, Compactor.NONE);
    }

    /** A strategy that only compacts (no editing). */
    public static ContextStrategy compacting(Compactor compactor) {
        return of(ContextEditor.NONE, compactor);
    }
}
