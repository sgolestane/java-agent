package dev.agentkit.core.memory;

import java.util.List;
import java.util.Optional;

/**
 * Durable, cross-session memory: a keyed store of text documents the agent
 * reads and writes to carry knowledge from one run to the next.
 *
 * <p>Paths are forward-slash-separated relative keys (e.g. {@code "facts/user.md"}).
 * Implementations must reject path traversal — see {@link FileMemoryStore}.
 *
 * <p><strong>Never store secrets here.</strong> Memory persists and is replayed
 * into future contexts; a credential written once is exposed to every later run.
 */
public interface MemoryStore {

    /** Reads the content at {@code path}, or empty if it does not exist. */
    Optional<String> read(String path);

    /** Writes (creating or replacing) {@code content} at {@code path}. */
    void write(String path, String content);

    /** Appends {@code content} to {@code path}, creating it if absent. */
    void append(String path, String content);

    /** Deletes {@code path}; returns whether anything was removed. */
    boolean delete(String path);

    /** Whether {@code path} exists. */
    boolean exists(String path);

    /** Lists existing paths that start with {@code prefix} (empty prefix lists all), sorted. */
    List<String> list(String prefix);
}
