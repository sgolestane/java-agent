package dev.agentkit.examples;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** A minimal thread-safe scripted {@link LlmClient} for the example integration tests. */
final class FakeLlm implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();

    FakeLlm then(LlmResponse response) {
        script.add(response);
        return this;
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        LlmResponse response = script.poll();
        if (response == null) {
            throw new LlmException("FakeLlm exhausted");
        }
        return response;
    }

    static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, new ToolUseBlock(id, name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }
}
