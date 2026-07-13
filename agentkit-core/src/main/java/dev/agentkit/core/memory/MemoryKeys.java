package dev.agentkit.core.memory;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Normalises {@link MemoryStore} keys so all implementations agree on what a path
 * means: forward-slash separators, collapsed {@code //}, stripped {@code ./} and
 * trailing slashes, and rejection of absolute paths, traversal, and the root.
 */
final class MemoryKeys {

    private MemoryKeys() {
    }

    static String normalize(String raw) {
        Objects.requireNonNull(raw, "path");
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path p;
        try {
            p = Path.of(trimmed).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path: '" + raw + "'");
        }
        if (p.isAbsolute()) {
            throw new IllegalArgumentException("path must be relative: '" + raw + "'");
        }
        String key = p.toString().replace('\\', '/');
        if (key.isEmpty() || key.equals(".")) {
            throw new IllegalArgumentException("path must denote a key inside memory: '" + raw + "'");
        }
        if (key.equals("..") || key.startsWith("../")) {
            throw new IllegalArgumentException("path escapes memory: '" + raw + "'");
        }
        return key;
    }
}
