package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryToolsTest {

    private static ToolResult run(Tool tool, Map<String, Object> args) {
        return tool.execute(new ToolInvocation("1", tool.name(), args));
    }

    @Test
    void memoryToolWriteThenRead() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "write", "path", "k.md", "content", "v")).isError()).isFalse();
        assertThat(run(memory, Map.of("command", "read", "path", "k.md")).content()).isEqualTo("v");
    }

    @Test
    void memoryToolReadMissingIsError() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "read", "path", "nope")).isError()).isTrue();
    }

    @Test
    void memoryToolListAndDelete() {
        MemoryStore store = new InMemoryMemoryStore();
        Tool memory = MemoryTools.memoryTool(store);
        run(memory, Map.of("command", "write", "path", "a.md", "content", "x"));
        assertThat(run(memory, Map.of("command", "list")).content()).contains("a.md");
        assertThat(run(memory, Map.of("command", "delete", "path", "a.md")).content()).contains("Deleted");
    }

    @Test
    void memoryToolRejectsMissingPath() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "read")).isError()).isTrue();
        assertThat(run(memory, Map.of("command", "write", "path", "a")).isError()).isTrue(); // no content
    }

    @Test
    void memoryToolUnknownCommand() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "frobnicate")).isError()).isTrue();
    }

    @Test
    void memoryToolTranslatesTraversalToError() {
        Tool memory = MemoryTools.memoryTool(new FileMemoryStore(java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "agentkit-mem-test-" + System.nanoTime())));
        ToolResult result = run(memory, Map.of("command", "write", "path", "../x", "content", "y"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void rememberAndRecall() {
        WorkingMemory wm = new WorkingMemory();
        Tool remember = MemoryTools.rememberTool(wm);
        Tool recall = MemoryTools.recallTool(wm);

        assertThat(run(recall, Map.of()).content()).contains("No notes");
        run(remember, Map.of("note", "user is named Alice"));
        assertThat(run(recall, Map.of()).content()).contains("user is named Alice");
    }

    @Test
    void memoryToolAppendAndListWithPrefix() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        run(memory, Map.of("command", "append", "path", "log.txt", "content", "one\n"));
        run(memory, Map.of("command", "append", "path", "log.txt", "content", "two\n"));
        assertThat(run(memory, Map.of("command", "read", "path", "log.txt")).content()).isEqualTo("one\ntwo\n");
        run(memory, Map.of("command", "write", "path", "notes/a.md", "content", "x"));
        assertThat(run(memory, Map.of("command", "list", "path", "notes/")).content()).isEqualTo("notes/a.md");
    }

    @Test
    void memoryToolDeleteMissingReportsNothing() {
        Tool memory = MemoryTools.memoryTool(new InMemoryMemoryStore());
        assertThat(run(memory, Map.of("command", "delete", "path", "gone")).content()).contains("Nothing to delete");
    }

    @Test
    void agentPersistsAndRecallsAcrossRuns(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        // Two independently-constructed durable stores over the same directory,
        // simulating two separate sessions with a process boundary in between.
        // Run 1: the model writes a fact to durable memory.
        SimpleToolRegistry reg1 = new SimpleToolRegistry().register(MemoryTools.memoryTool(new FileMemoryStore(root)));
        FakeLlmClient llm1 = new FakeLlmClient(
                FakeLlmClient.toolUse("m1", "memory",
                        Map.of("command", "write", "path", "facts/color.md", "content", "blue")),
                FakeLlmClient.text("Saved."));
        new Agent(llm1, reg1, AgentConfig.builder("m").maxSteps(5).build())
                .run(Goal.of("remember my favorite color is blue"));

        // Run 2 (new agent, freshly-constructed store over the same dir): reads it back.
        SimpleToolRegistry reg2 = new SimpleToolRegistry().register(MemoryTools.memoryTool(new FileMemoryStore(root)));
        FakeLlmClient llm2 = new FakeLlmClient(
                FakeLlmClient.toolUse("m2", "memory", Map.of("command", "read", "path", "facts/color.md")),
                FakeLlmClient.text("Your favorite color is blue."));
        AgentResult r2 = new Agent(llm2, reg2, AgentConfig.builder("m").maxSteps(5).build())
                .run(Goal.of("what is my favorite color?"));

        assertThat(r2.output()).contains("blue");
        var toolMsg = llm2.received().get(1).messages().get(2);
        assertThat(((dev.agentkit.core.message.ToolResultBlock) toolMsg.content().get(0)).content())
                .isEqualTo("blue");
    }
}
