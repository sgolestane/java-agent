package dev.agentkit.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-session scratch memory: an ordered list of short notes the agent jots to
 * itself while pursuing a goal.
 *
 * <p>Unlike {@link MemoryStore}, working memory is <em>transient</em> — it lives
 * for the duration of one run and is not persisted. {@link #render()} is the
 * authoritative way to surface notes: the context builder (Phase 6) re-injects
 * them so key facts survive compaction, and a {@code recall} tool is only a
 * fallback for pulling them mid-turn. Not thread-safe.
 */
public final class WorkingMemory {

    private final List<String> notes = new ArrayList<>();

    /** Appends a note. Blank notes are ignored. */
    public WorkingMemory note(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.isBlank()) {
            notes.add(text.strip());
        }
        return this;
    }

    /** An unmodifiable snapshot of the notes, oldest first. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public void clear() {
        notes.clear();
    }

    /** Renders the notes as a bulleted block, or empty string if there are none. */
    public String render() {
        if (notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Working notes:\n");
        for (String note : notes) {
            sb.append("- ").append(note).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
