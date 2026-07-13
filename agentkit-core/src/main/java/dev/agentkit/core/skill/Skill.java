package dev.agentkit.core.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A reusable, progressively-disclosed unit of expertise, modelled on the
 * {@code SKILL.md} convention.
 *
 * <p>A skill discloses in three tiers to protect the context window:
 * <ol>
 *   <li><b>metadata</b> — {@link #name()} + {@link #description()} sit in context
 *       from the start (cheap; used by the model to decide relevance);</li>
 *   <li><b>instructions</b> — the full {@link #instructions() body} is loaded only
 *       when the skill is triggered;</li>
 *   <li><b>resources</b> — bundled files under {@link #directory()} are read only
 *       when actually needed during execution.</li>
 * </ol>
 *
 * @param name         unique skill name; never {@code null} or blank
 * @param description  when-to-use description used for tier-1 disclosure
 * @param instructions the full markdown body (tier 2); never {@code null}
 * @param directory    the skill directory holding bundled resources, if any
 * @param resourceFiles relative paths of bundled resource files (tier 3)
 */
public record Skill(String name, String description, String instructions,
                    Optional<Path> directory, List<String> resourceFiles) {

    public Skill {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(resourceFiles, "resourceFiles");
        resourceFiles = List.copyOf(resourceFiles);
    }

    /** An in-memory skill with no bundled resource directory. */
    public static Skill of(String name, String description, String instructions) {
        return new Skill(name, description, instructions, Optional.empty(), List.of());
    }

    public boolean hasResources() {
        return !resourceFiles.isEmpty();
    }
}
