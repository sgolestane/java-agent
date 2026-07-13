package dev.agentkit.core.llm;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A scripted {@link LlmClient} for tests: returns pre-built responses in order
 * and records every request it receives.
 */
public final class FakeLlmClient implements LlmClient {

    private final Deque<LlmResponse> scripted = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();

    public FakeLlmClient(LlmResponse... responses) {
        for (LlmResponse r : responses) {
            scripted.add(r);
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        received.add(request);
        if (scripted.isEmpty()) {
            throw new LlmException("FakeLlmClient exhausted after " + received.size() + " calls");
        }
        return scripted.poll();
    }

    public List<LlmRequest> received() {
        return List.copyOf(received);
    }

    // --- response factories -------------------------------------------------

    public static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    public static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, new ToolUseBlock(id, name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    public static LlmResponse refusal(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.REFUSAL, TokenUsage.ZERO);
    }

    public static LlmResponse maxTokens(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.MAX_TOKENS, TokenUsage.ZERO);
    }

    public static LlmResponse pause(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.PAUSE, TokenUsage.ZERO);
    }

    public static LlmResponse textWithUsage(String text, TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, usage);
    }

    public static LlmResponse toolUseWithUsage(String id, String name, Map<String, Object> input,
                                               TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, new ToolUseBlock(id, name, input)),
                LlmStopReason.TOOL_USE, usage);
    }
}
