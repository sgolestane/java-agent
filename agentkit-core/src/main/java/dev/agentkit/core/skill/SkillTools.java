package dev.agentkit.core.skill;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the tools that drive tiers 2 and 3 of skill progressive disclosure over
 * a {@link SkillLibrary}:
 *
 * <ul>
 *   <li>{@code read_skill(name)} — loads a skill's full instructions (tier 2);</li>
 *   <li>{@code read_skill_resource(skill, path)} — reads a bundled resource file
 *       (tier 3), confined to the skill directory.</li>
 * </ul>
 *
 * <p>Register these with a tool registry and inject {@link SkillLibrary#catalog()}
 * into the system prompt to complete the three-tier model.
 */
public final class SkillTools {

    public static final String READ_SKILL = "read_skill";
    public static final String READ_SKILL_RESOURCE = "read_skill_resource";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SkillTools.class);

    private SkillTools() {
    }

    /** The read_skill and read_skill_resource tools for {@code library}. */
    public static List<Tool> forLibrary(SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        return List.of(readSkillTool(library), readSkillResourceTool(library));
    }

    public static Tool readSkillTool(SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of(
                        "type", "string", "description", "The skill name from the catalog")),
                "required", List.of("name"));
        return FunctionTool.builder(READ_SKILL,
                        "Load a skill's full instructions before using it. Pass the skill name "
                                + "shown in the skills catalog.")
                .schema(schema)
                .handler(inv -> readSkill(library, inv))
                .build();
    }

    public static Tool readSkillResourceTool(SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill", Map.of("type", "string", "description", "The skill name"),
                        "path", Map.of("type", "string",
                                "description", "Relative path of the bundled resource file")),
                "required", List.of("skill", "path"));
        return FunctionTool.builder(READ_SKILL_RESOURCE,
                        "Read a bundled resource file belonging to a skill, by its relative path.")
                .schema(schema)
                .handler(inv -> readResource(library, inv))
                .build();
    }

    private static ToolResult readSkill(SkillLibrary library, ToolInvocation inv) {
        String name = inv.stringArgument("name");
        if (name == null || name.isBlank()) {
            return ToolResult.error("The 'name' argument is required.");
        }
        Optional<Skill> skill = library.find(name);
        if (skill.isEmpty()) {
            return ToolResult.error("No skill named '" + name + "'.");
        }
        return ToolResult.ok(skill.get().renderInstructions(READ_SKILL_RESOURCE));
    }

    private static ToolResult readResource(SkillLibrary library, ToolInvocation inv) {
        String name = inv.stringArgument("skill");
        String path = inv.stringArgument("path");
        if (name == null || name.isBlank() || path == null || path.isBlank()) {
            return ToolResult.error("Both 'skill' and 'path' arguments are required.");
        }
        Optional<Skill> skill = library.find(name);
        if (skill.isEmpty()) {
            return ToolResult.error("No skill named '" + name + "'.");
        }
        Optional<Path> dir = skill.get().directory();
        if (dir.isEmpty()) {
            return ToolResult.error("Skill '" + name + "' has no bundled resources.");
        }
        Path resolved;
        try {
            resolved = SafePaths.resolveWithin(dir.get(), path);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(resolved)) {
            return ToolResult.error("No such resource file: '" + path + "'.");
        }
        try {
            return ToolResult.ok(Files.readString(resolved, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Do not surface the absolute path / host layout to the model.
            LOG.warn("Failed to read skill resource {} for skill {}", path, name, e);
            return ToolResult.error("Failed to read resource '" + path + "'.");
        }
    }
}
