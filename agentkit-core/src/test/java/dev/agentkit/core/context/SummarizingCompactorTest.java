package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummarizingCompactorTest {

    private static SummarizingCompactor compactor(LlmClient llm, int trigger, int keep) {
        return SummarizingCompactor.builder(llm, "m")
                .triggerTokens(trigger).keepRecentMessages(keep).build();
    }

    @Test
    void belowTriggerLeavesHistoryUnchanged() {
        List<Message> history = List.of(Message.user("short"), Message.assistant("ok"));
        Compactor c = compactor(new FakeLlmClient(FakeLlmClient.text("SUMMARY")), 1_000_000, 2);
        assertThat(c.compact(history)).isSameAs(history);
    }

    @Test
    void aboveTriggerReplacesHeadWithSummary() {
        List<Message> history = List.of(
                Message.user("goal"), Message.assistant("a"),
                Message.user("b"), Message.assistant("c"), Message.user("d"));
        Compactor c = compactor(new FakeLlmClient(FakeLlmClient.text("SUMMARY")), 1, 2);

        List<Message> compacted = c.compact(history);

        assertThat(compacted).hasSize(3); // summary + 2 recent
        assertThat(compacted.get(0).role()).isEqualTo(Role.USER);
        assertThat(compacted.get(0).text()).startsWith("[Summary").contains("SUMMARY");
        assertThat(compacted.get(1)).isEqualTo(Message.assistant("c"));
        assertThat(compacted.get(2)).isEqualTo(Message.user("d"));
    }

    @Test
    void boundaryNeverOrphansAToolResult() {
        List<Message> history = List.of(
                Message.user("goal"),
                Message.of(Role.ASSISTANT, new ToolUseBlock("t1", "tool", Map.of())),
                Message.of(Role.USER, ToolResultBlock.ok("t1", "result")),
                Message.assistant("continuing"),
                Message.user("e"),
                Message.user("f"));
        // keepRecent=4 would cut at the tool_result (index 2); the boundary must advance.
        Compactor c = compactor(new FakeLlmClient(FakeLlmClient.text("SUMMARY")), 1, 4);

        List<Message> compacted = c.compact(history);

        // First surviving non-summary message must not carry an orphaned tool_result.
        assertThat(compacted.get(1).content()).noneMatch(b -> b instanceof ToolResultBlock);
        assertThat(compacted.get(1)).isEqualTo(Message.assistant("continuing"));
    }

    @Test
    void overTriggerWithFewerMessagesThanWindowDoesNotCrash() {
        // A tiny-but-huge history (over trigger) with keepRecent(6) default: cut
        // would be negative — must clamp and return unchanged, not throw.
        List<Message> history = List.of(Message.user("goal"), Message.assistant("a"));
        // FakeLlmClient with no scripted responses: summarise() must not be called.
        Compactor c = SummarizingCompactor.builder(new FakeLlmClient(), "m")
                .triggerTokens(1).keepRecentMessages(6).build();
        assertThat(c.compact(history)).isSameAs(history);
    }

    @Test
    void systemPromptOverrideIsUsed() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("SUMMARY"));
        Compactor c = SummarizingCompactor.builder(llm, "m")
                .triggerTokens(1).keepRecentMessages(0).systemPrompt("CUSTOM PROMPT").build();
        c.compact(List.of(Message.user("goal"), Message.assistant("a"), Message.user("b")));
        assertThat(llm.received().get(0).system()).contains("CUSTOM PROMPT");
    }

    @Test
    void summarisationFailureKeepsFullHistory() {
        LlmClient failing = request -> {
            throw new LlmException("summariser down");
        };
        List<Message> history = List.of(
                Message.user("goal"), Message.assistant("a"),
                Message.user("b"), Message.assistant("c"), Message.user("d"));
        assertThat(compactor(failing, 1, 2).compact(history)).isSameAs(history);
    }
}
