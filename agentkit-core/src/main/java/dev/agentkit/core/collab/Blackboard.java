package dev.agentkit.core.collab;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A concurrency-safe shared workspace ("blackboard") that collaborating agents
 * post to and read from, so several agents can build on each other's partial
 * work instead of each starting fresh.
 *
 * <p>Entries are append-only and carry a monotonically increasing {@link Entry#id()}
 * assigned at post time, so a reader can page forward from the last id it saw. The
 * board is safe to share across the threads a {@code Supervisor} fans out onto.
 * Expose it to agents as tools via {@link BlackboardTools}.
 */
public final class Blackboard {

    /**
     * One post on the board.
     *
     * @param id      monotonic, assigned at post time; strictly increasing
     * @param author  who posted it (an agent name)
     * @param topic   a short label used to group and filter related posts
     * @param content the note body
     */
    public record Entry(long id, String author, String topic, String content) {
        public Entry {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(content, "content");
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /** Appends a note and returns the stored entry (with its assigned id). */
    public Entry post(String author, String topic, String content) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(content, "content");
        Entry entry = new Entry(nextId.getAndIncrement(), author, topic, content);
        entries.add(entry);
        return entry;
    }

    /** A snapshot of every entry, in post order. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Entries whose topic equals {@code topic} (case-insensitive), in post order. */
    public List<Entry> byTopic(String topic) {
        Objects.requireNonNull(topic, "topic");
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.topic().equalsIgnoreCase(topic)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /** Entries posted after {@code afterId} (exclusive), in post order. */
    public List<Entry> since(long afterId) {
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id() > afterId) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /** The number of entries currently on the board. */
    public int size() {
        return entries.size();
    }
}
