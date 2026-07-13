package dev.agentkit.core.util;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Helpers for confining model-supplied relative paths to a trusted base
 * directory, preventing path-traversal escapes ({@code ..}, absolute paths).
 *
 * <p>Used wherever the model controls a path — reading skill resources (Phase 3)
 * and the file-backed memory store (Phase 5).
 */
public final class SafePaths {

    private SafePaths() {
    }

    /**
     * Resolves {@code requested} against {@code baseDir} and verifies the result
     * stays within {@code baseDir}.
     *
     * <p>This is a lexical check (via {@link Path#normalize()}); it does not
     * resolve symlinks. When operating on untrusted trees where symlinks could
     * point outside the base, additionally verify the real path after resolution.
     *
     * @return the normalised, absolute path inside {@code baseDir}
     * @throws IllegalArgumentException if {@code requested} escapes {@code baseDir}
     */
    public static Path resolveWithin(Path baseDir, String requested) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(requested, "requested");
        if (requested.isBlank()) {
            throw new IllegalArgumentException("requested path must not be blank");
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(requested).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            // Reject escapes above the base AND the base directory itself: a
            // model-supplied key must denote a file strictly inside the base.
            throw new IllegalArgumentException(
                    "Path '" + requested + "' does not resolve to a location inside the permitted directory");
        }
        return resolved;
    }
}
