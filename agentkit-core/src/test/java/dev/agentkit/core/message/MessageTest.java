package dev.agentkit.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void userFactoryBuildsSingleTextBlock() {
        Message m = Message.user("hello");

        assertThat(m.role()).isEqualTo(Role.USER);
        assertThat(m.content()).containsExactly(TextBlock.of("hello"));
        assertThat(m.text()).isEqualTo("hello");
    }

    @Test
    void textConcatenatesOnlyTextBlocks() {
        Message m = new Message(Role.ASSISTANT, List.of(
                TextBlock.of("part one"),
                new ToolUseBlock("t1", "search", Map.of("q", "x")),
                TextBlock.of("part two")));

        assertThat(m.text()).isEqualTo("part one\npart two");
    }

    @Test
    void contentIsImmutable() {
        Message m = Message.user("x");
        assertThatThrownBy(() -> m.content().add(TextBlock.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyContentIsRejected() {
        assertThatThrownBy(() -> new Message(Role.USER, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolUseInputIsDefensivelyCopiedAndUnmodifiable() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("a", 1);
        ToolUseBlock block = new ToolUseBlock("id", "tool", mutable);

        mutable.put("b", 2); // must not leak into the block
        assertThat(block.input()).containsOnlyKeys("a");
        assertThatThrownBy(() -> block.input().put("c", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolUseInputPermitsNullValues() {
        var input = new java.util.HashMap<String, Object>();
        input.put("optional", null);
        ToolUseBlock block = new ToolUseBlock("id", "tool", input);

        assertThat(block.input()).containsKey("optional");
        assertThat(block.input().get("optional")).isNull();
    }

    @Test
    void textIsEmptyForToolOnlyMessage() {
        Message m = Message.of(Role.ASSISTANT,
                new ToolUseBlock("t1", "search", Map.of("q", "x")));
        assertThat(m.text()).isEmpty();
    }

    @Test
    void toolResultFactoriesSetErrorFlag() {
        assertThat(ToolResultBlock.ok("id", "done").isError()).isFalse();
        assertThat(ToolResultBlock.error("id", "boom").isError()).isTrue();
    }
}
