package dev.agentkit.temporal;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe scripted {@link LlmClient} for the durable tests. Entries are
 * consumed in order across activity invocations (which run on worker threads); a
 * {@link #fail()} entry throws once to exercise Temporal's activity retry. The
 * total number of {@code generate} calls is recorded so a test can prove that a
 * completed activity is not re-executed when a later one is retried.
 */
final class ScriptedLlm implements LlmClient {

    private sealed interface Entry permits Reply, Fail {
    }

    private record Reply(LlmResponse response) implements Entry {
    }

    private record Fail() implements Entry {
    }

    private final Deque<Entry> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();

    ScriptedLlm then(LlmResponse response) {
        script.add(new Reply(response));
        return this;
    }

    /** Scripts a single transient failure at this point in the sequence. */
    ScriptedLlm fail() {
        script.add(new Fail());
        return this;
    }

    int callCount() {
        return calls.get();
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        calls.incrementAndGet();
        Entry entry = script.poll();
        if (entry == null) {
            throw new LlmException("ScriptedLlm exhausted after " + calls.get() + " calls");
        }
        if (entry instanceof Fail) {
            throw new LlmException("scripted transient failure");
        }
        return ((Reply) entry).response();
    }

    // --- response factories -------------------------------------------------

    static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, new ToolUseBlock(id, name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    static LlmResponse refusal(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.REFUSAL, TokenUsage.ZERO);
    }
}
