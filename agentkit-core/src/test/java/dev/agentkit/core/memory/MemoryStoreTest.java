package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MemoryStoreTest {

    @TempDir
    static Path tempRoot;

    static Stream<MemoryStore> stores() {
        return Stream.of(new InMemoryMemoryStore(), new FileMemoryStore(tempRoot.resolve("s")));
    }

    @ParameterizedTest
    @MethodSource("stores")
    void writeReadExistsDelete(MemoryStore store) {
        assertThat(store.read("a/b.md")).isEmpty();
        store.write("a/b.md", "hello");
        assertThat(store.exists("a/b.md")).isTrue();
        assertThat(store.read("a/b.md")).contains("hello");
        assertThat(store.delete("a/b.md")).isTrue();
        assertThat(store.exists("a/b.md")).isFalse();
        assertThat(store.delete("a/b.md")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("stores")
    void appendConcatenates(MemoryStore store) {
        store.append("log.txt", "one\n");
        store.append("log.txt", "two\n");
        assertThat(store.read("log.txt")).contains("one\ntwo\n");
    }

    @ParameterizedTest
    @MethodSource("stores")
    void listReturnsSortedMatchingKeys(MemoryStore store) {
        store.write("notes/b.md", "b");
        store.write("notes/a.md", "a");
        store.write("other.md", "o");
        assertThat(store.list("notes/")).containsExactly("notes/a.md", "notes/b.md");
        assertThat(store.list("")).contains("notes/a.md", "notes/b.md", "other.md");
    }

    @org.junit.jupiter.api.Test
    void fileStorePersistsAcrossInstances(@TempDir Path root) {
        new FileMemoryStore(root).write("facts/user.md", "prefers dark mode");
        // A fresh store (simulating a later session) sees the persisted memory.
        assertThat(new FileMemoryStore(root).read("facts/user.md")).contains("prefers dark mode");
    }

    @org.junit.jupiter.api.Test
    void fileStoreRejectsTraversal(@TempDir Path root) {
        FileMemoryStore store = new FileMemoryStore(root);
        assertThatThrownBy(() -> store.write("../escape.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    void inMemoryStoreRejectsBlankPath() {
        assertThatThrownBy(() -> new InMemoryMemoryStore().write("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
