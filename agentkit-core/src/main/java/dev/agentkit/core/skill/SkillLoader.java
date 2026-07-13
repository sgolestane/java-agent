package dev.agentkit.core.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads {@link Skill skills} from the filesystem.
 *
 * <p>A skill is a directory containing a {@code SKILL.md}; every other regular
 * file under that directory is registered as a bundled resource (tier 3).
 */
public final class SkillLoader {

    /** The conventional skill definition file name. */
    public static final String SKILL_FILE = "SKILL.md";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SkillLoader.class);

    private SkillLoader() {
    }

    /**
     * Loads every skill directory directly under {@code root} (a directory is a
     * skill directory iff it contains a {@code SKILL.md}).
     *
     * <p><strong>Resilient by design:</strong> a directory whose skill fails to
     * parse or load is skipped with a logged warning rather than aborting the
     * whole load — one malformed skill must not disable every other skill in an
     * unsupervised deployment. Use {@link #loadSkill(Path)} directly when you want
     * a single skill's failure to propagate.
     */
    public static List<Skill> loadDirectory(Path root) {
        Objects.requireNonNull(root, "root");
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> skillDirs = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(SKILL_FILE)))
                    .sorted()
                    .toList();
            for (Path dir : skillDirs) {
                try {
                    skills.add(loadSkill(dir));
                } catch (RuntimeException e) {
                    LOG.warn("Skipping malformed skill at {}: {}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list skill directory: " + root, e);
        }
        return skills;
    }

    /** Loads a single skill from its directory. */
    public static Skill loadSkill(Path skillDir) {
        Objects.requireNonNull(skillDir, "skillDir");
        Path md = skillDir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(md)) {
            throw new IllegalArgumentException("No " + SKILL_FILE + " in " + skillDir);
        }
        Path base = skillDir.toAbsolutePath().normalize();
        try {
            String content = Files.readString(md, StandardCharsets.UTF_8);
            SkillParser.Parsed parsed = SkillParser.parse(content);
            List<String> resources = listResources(base);
            return new Skill(parsed.name(), parsed.description(), parsed.body(),
                    Optional.of(base), resources);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill at " + skillDir, e);
        }
    }

    private static List<String> listResources(Path base) throws IOException {
        try (Stream<Path> walk = Files.walk(base)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(name -> !name.equals(SKILL_FILE))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
