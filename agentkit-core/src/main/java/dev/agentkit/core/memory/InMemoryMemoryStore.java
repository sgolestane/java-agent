package dev.agentkit.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A non-persistent {@link MemoryStore} backed by a map. Useful for tests and for
 * a supervisor to hand a subagent scratch memory that vanishes with the process.
 * Not thread-safe.
 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final TreeMap<String, String> store = new TreeMap<>();

    private static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return path.strip();
    }

    @Override
    public Optional<String> read(String path) {
        return Optional.ofNullable(store.get(normalize(path)));
    }

    @Override
    public void write(String path, String content) {
        store.put(normalize(path), Objects.requireNonNull(content, "content"));
    }

    @Override
    public void append(String path, String content) {
        Objects.requireNonNull(content, "content");
        store.merge(normalize(path), content, String::concat);
    }

    @Override
    public boolean delete(String path) {
        return store.remove(normalize(path)) != null;
    }

    @Override
    public boolean exists(String path) {
        return store.containsKey(normalize(path));
    }

    @Override
    public List<String> list(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        List<String> keys = new ArrayList<>();
        for (String key : store.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        return keys;
    }
}
