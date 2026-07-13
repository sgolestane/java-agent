package dev.agentkit.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class SkillFilesystemTest {

    private static Path writeSkill(Path root, String name, String description, String body)
            throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
        return dir;
    }

    @Test
    void loadsSkillsWithResources(@TempDir Path root) throws IOException {
        Path dir = writeSkill(root, "reporter", "Write reports", "Follow the template.");
        Files.writeString(dir.resolve("template.md"), "# Report template");
        writeSkill(root, "emailer", "Send emails", "Be concise.");

        List<Skill> skills = SkillLoader.loadDirectory(root);

        assertThat(skills).extracting(Skill::name).containsExactly("emailer", "reporter");
        Skill reporter = skills.stream().filter(s -> s.name().equals("reporter")).findFirst().orElseThrow();
        assertThat(reporter.resourceFiles()).containsExactly("template.md");
        assertThat(reporter.directory()).isPresent();
    }

    @Test
    void readSkillToolReturnsInstructionsAndResourceListing(@TempDir Path root) throws IOException {
        Path dir = writeSkill(root, "reporter", "Write reports", "Follow the template.");
        Files.writeString(dir.resolve("template.md"), "# Report template");
        SkillLibrary library = new SkillLibrary(SkillLoader.loadDirectory(root));

        Tool readSkill = SkillTools.readSkillTool(library);
        ToolResult result = readSkill.execute(new ToolInvocation("1", "read_skill", Map.of("name", "reporter")));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Follow the template.").contains("template.md");
    }

    @Test
    void readSkillResourceReadsBundledFile(@TempDir Path root) throws IOException {
        Path dir = writeSkill(root, "reporter", "Write reports", "body");
        Files.writeString(dir.resolve("template.md"), "# Report template");
        SkillLibrary library = new SkillLibrary(SkillLoader.loadDirectory(root));

        Tool tool = SkillTools.readSkillResourceTool(library);
        ToolResult result = tool.execute(new ToolInvocation("1", "read_skill_resource",
                Map.of("skill", "reporter", "path", "template.md")));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("# Report template");
    }

    @Test
    void readSkillResourceRejectsTraversal(@TempDir Path root) throws IOException {
        writeSkill(root, "reporter", "Write reports", "body");
        Files.writeString(root.resolve("secret.txt"), "top secret");
        SkillLibrary library = new SkillLibrary(SkillLoader.loadDirectory(root));

        Tool tool = SkillTools.readSkillResourceTool(library);
        ToolResult result = tool.execute(new ToolInvocation("1", "read_skill_resource",
                Map.of("skill", "reporter", "path", "../secret.txt")));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).doesNotContain("top secret");
    }

    @Test
    void readSkillUnknownNameIsError(@TempDir Path root) {
        SkillLibrary library = new SkillLibrary();
        ToolResult result = SkillTools.readSkillTool(library)
                .execute(new ToolInvocation("1", "read_skill", Map.of("name", "ghost")));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void readResourceOnInMemorySkillIsError() {
        SkillLibrary library = new SkillLibrary().add(Skill.of("s", "d", "i"));
        ToolResult result = SkillTools.readSkillResourceTool(library).execute(
                new ToolInvocation("1", "read_skill_resource", Map.of("skill", "s", "path", "x.txt")));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("no bundled resources");
    }

    @Test
    void readResourceRejectsDirectoryPath(@TempDir Path root) throws IOException {
        Path dir = writeSkill(root, "s", "d", "body");
        Files.createDirectory(dir.resolve("sub"));
        SkillLibrary library = new SkillLibrary(SkillLoader.loadDirectory(root));
        ToolResult result = SkillTools.readSkillResourceTool(library).execute(
                new ToolInvocation("1", "read_skill_resource", Map.of("skill", "s", "path", "sub")));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void loadDirectoryWithNoSkillsReturnsEmpty(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("not-a-skill"));
        assertThat(SkillLoader.loadDirectory(root)).isEmpty();
    }

    @Test
    void malformedSkillIsSkippedNotFatal(@TempDir Path root) throws IOException {
        writeSkill(root, "good", "a good skill", "body");
        Path bad = Files.createDirectory(root.resolve("bad"));
        Files.writeString(bad.resolve("SKILL.md"), "no frontmatter here");

        List<Skill> skills = SkillLoader.loadDirectory(root);
        assertThat(skills).extracting(Skill::name).containsExactly("good");
    }
}
