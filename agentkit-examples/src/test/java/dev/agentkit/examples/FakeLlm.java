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
    private int served;

    FakeLlm then(LlmResponse response) {
        script.add(response);
        return this;
    }

    /** Scripted responses not yet consumed — assert 0 to catch over-scripting. */
    synchronized int remaining() {
        return script.size();
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        LlmResponse response = script.poll();
        if (response == null) {
            throw new LlmException("FakeLlm exhausted after serving " + served
                    + " responses; the script is too short for what the agent requested");
        }
        served++;
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
