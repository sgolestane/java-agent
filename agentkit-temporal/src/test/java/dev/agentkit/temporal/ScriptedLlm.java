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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
    private final List<LlmRequest> requests = new ArrayList<>();

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

    /** The requests received so far, in order (thread-safe snapshot). */
    synchronized List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        calls.incrementAndGet();
        requests.add(request);
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

    static LlmResponse textWithUsage(String text, TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, usage);
    }

    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return toolUseWithUsage(id, name, input, TokenUsage.ZERO);
    }

    static LlmResponse toolUseWithUsage(String id, String name, Map<String, Object> input,
                                        TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, new ToolUseBlock(id, name, input)),
                LlmStopReason.TOOL_USE, usage);
    }

    /** An assistant turn carrying several tool_use blocks at once. */
    static LlmResponse multiToolUse(List<ToolUseBlock> blocks) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, List.copyOf(blocks)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    static LlmResponse refusal(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.REFUSAL, TokenUsage.ZERO);
    }

    static LlmResponse maxTokens(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.MAX_TOKENS, TokenUsage.ZERO);
    }

    static LlmResponse pause(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.PAUSE, TokenUsage.ZERO);
    }
}
