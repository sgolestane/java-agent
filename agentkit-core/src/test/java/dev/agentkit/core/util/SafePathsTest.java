package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class SafePathsTest {

    private static final Path BASE = Paths.get("/srv/skills/pdf");

    @Test
    void resolvesRelativePathWithinBase() {
        Path resolved = SafePaths.resolveWithin(BASE, "forms/w2.txt");
        assertThat(resolved).isEqualTo(BASE.toAbsolutePath().normalize().resolve("forms/w2.txt"));
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(BASE, "../secrets.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSneakyTraversal() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(BASE, "forms/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsoluteEscape() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(BASE, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(BASE, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
