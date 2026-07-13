package dev.agentkit.core.memory;

import dev.agentkit.core.util.SafePaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link MemoryStore} backed by a directory on disk, so memory survives process
 * restarts and is shared across runs that point at the same root.
 *
 * <p>All keys are confined to the root via {@link SafePaths}, rejecting traversal
 * ({@code ..}, absolute paths) and the root itself. Confinement is <em>lexical</em>:
 * a symlink placed inside the root by some other process could still point outside
 * it — assume the memory directory holds only content this agent writes. Not
 * thread-safe for concurrent writers to the same path.
 */
public final class FileMemoryStore implements MemoryStore {

    private final Path root;

    public FileMemoryStore(Path root) {
        Objects.requireNonNull(root, "root");
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create memory root: " + this.root, e);
        }
    }

    private Path resolve(String path) {
        return SafePaths.resolveWithin(root, path);
    }

    @Override
    public Optional<String> read(String path) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read memory " + path, e);
        }
    }

    @Override
    public void write(String path, String content) {
        Objects.requireNonNull(content, "content");
        Path file = resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write memory " + path, e);
        }
    }

    @Override
    public void append(String path, String content) {
        Objects.requireNonNull(content, "content");
        Path file = resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append memory " + path, e);
        }
    }

    @Override
    public boolean delete(String path) {
        try {
            return Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete memory " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(resolve(path));
    }

    @Override
    public List<String> list(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> keys = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(file -> {
                String key = root.relativize(file).toString().replace('\\', '/');
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            });
            keys.sort(String::compareTo);
            return keys;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list memory", e);
        }
    }
}
