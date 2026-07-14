package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlackboardToolsTest {

    private static ToolResult exec(Tool tool, Map<String, Object> args) {
        return tool.execute(new ToolInvocation("i", tool.name(), args));
    }

    @Test
    void postNoteAttributesToTheBoundAuthorAndStrips() {
        Blackboard board = new Blackboard();
        Tool post = BlackboardTools.postNoteTool(board, "alice");

        ToolResult result = exec(post, Map.of("topic", "  plan  ", "content", "  do X  "));

        assertThat(result.isError()).isFalse();
        assertThat(board.entries()).singleElement().satisfies(e -> {
            assertThat(e.author()).isEqualTo("alice");   // author is bound, not model-supplied
            assertThat(e.topic()).isEqualTo("plan");      // stripped
            assertThat(e.content()).isEqualTo("do X");
        });
        assertThat(result.content()).contains("#1").contains("plan");
    }

    @Test
    void postNoteRequiresTopicAndContent() {
        Blackboard board = new Blackboard();
        Tool post = BlackboardTools.postNoteTool(board, "alice");

        assertThat(exec(post, Map.of("content", "x")).isError()).isTrue();
        assertThat(exec(post, Map.of("topic", "t")).isError()).isTrue();
        assertThat(exec(post, Map.of("topic", "  ", "content", "x")).isError()).isTrue();
        assertThat(board.size()).isZero();
    }

    @Test
    void readBoardRendersAllOrFiltersByTopic() {
        Blackboard board = new Blackboard();
        board.post("alice", "research", "found A");
        board.post("bob", "writing", "drafted B");
        Tool read = BlackboardTools.readBoardTool(board);

        ToolResult all = exec(read, Map.of());
        assertThat(all.content()).contains("found A").contains("drafted B").contains("by alice");

        ToolResult filtered = exec(read, Map.of("topic", "research"));
        assertThat(filtered.content()).contains("found A").doesNotContain("drafted B");
    }

    @Test
    void readBoardReportsEmptyStates() {
        Blackboard board = new Blackboard();
        Tool read = BlackboardTools.readBoardTool(board);

        assertThat(exec(read, Map.of()).content()).contains("empty");

        board.post("alice", "research", "x");
        assertThat(exec(read, Map.of("topic", "missing")).content())
                .contains("No notes under topic 'missing'");
    }
}
