package dev.agentkit.core.context;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Compactor} that summarises older turns with a model call once the
 * estimated history size crosses a trigger, keeping a recent window verbatim.
 *
 * <p>The compaction boundary is chosen to never orphan a {@code tool_result}
 * (its {@code tool_use} would otherwise be summarised away), so the surviving
 * transcript stays valid. On a summarisation failure the original history is
 * returned unchanged — compaction degrades gracefully rather than dropping data.
 */
public final class SummarizingCompactor implements Compactor {

    private static final Logger log = LoggerFactory.getLogger(SummarizingCompactor.class);

    private static final String SUMMARY_SYSTEM = """
            You compress an AI agent transcript. Produce a concise summary that a \
            fresh reader could use to continue the task: preserve goals, key facts \
            discovered, decisions made, tool results that still matter, and any \
            open sub-tasks. Omit chit-chat. Output only the summary.""";

    private static final String SUMMARY_PREFIX = "[Summary of earlier conversation]\n";

    private final LlmClient llm;
    private final String model;
    private final TokenEstimator estimator;
    private final int triggerTokens;
    private final int keepRecentMessages;
    private final int summaryMaxTokens;

    private SummarizingCompactor(Builder b) {
        this.llm = Objects.requireNonNull(b.llm, "llm");
        this.model = Objects.requireNonNull(b.model, "model");
        this.estimator = b.estimator;
        this.triggerTokens = b.triggerTokens;
        this.keepRecentMessages = b.keepRecentMessages;
        this.summaryMaxTokens = b.summaryMaxTokens;
    }

    public static Builder builder(LlmClient llm, String model) {
        return new Builder(llm, model);
    }

    @Override
    public List<Message> compact(List<Message> history) {
        if (estimator.estimate(history) <= triggerTokens) {
            return history;
        }
        int cut = history.size() - keepRecentMessages;
        // Never let the surviving tail begin with an orphaned tool_result.
        while (cut < history.size() && containsToolResult(history.get(cut))) {
            cut++;
        }
        if (cut <= 1) {
            return history; // nothing meaningful to summarise beyond the goal
        }

        List<Message> head = history.subList(0, cut);
        List<Message> tail = history.subList(cut, history.size());

        String summary;
        try {
            summary = summarise(head);
        } catch (RuntimeException e) {
            log.warn("Compaction summarisation failed; keeping full history", e);
            return history;
        }

        List<Message> compacted = new ArrayList<>(tail.size() + 1);
        compacted.add(Message.user(SUMMARY_PREFIX + summary));
        compacted.addAll(tail);
        return compacted;
    }

    private String summarise(List<Message> head) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : head) {
            transcript.append(message.role()).append(": ").append(renderBlocks(message)).append('\n');
        }
        LlmRequest request = LlmRequest.builder(model)
                .system(SUMMARY_SYSTEM)
                .maxTokens(summaryMaxTokens)
                .addMessage(Message.user(transcript.toString()))
                .build();
        LlmResponse response = llm.generate(request);
        return response.message().text();
    }

    private static String renderBlocks(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock t -> sb.append(t.text());
                case ThinkingBlock t -> sb.append("(thinking) ").append(t.thinking());
                case ToolUseBlock u -> sb.append("[calls ").append(u.name()).append(' ').append(u.input()).append(']');
                case ToolResultBlock r -> sb.append("[tool result: ").append(r.content()).append(']');
            }
            sb.append(' ');
        }
        return sb.toString().strip();
    }

    private static boolean containsToolResult(Message message) {
        return message.content().stream().anyMatch(ToolResultBlock.class::isInstance);
    }

    /** Builder for {@link SummarizingCompactor}. */
    public static final class Builder {
        private final LlmClient llm;
        private final String model;
        private TokenEstimator estimator = TokenEstimator.HEURISTIC;
        private int triggerTokens = 100_000;
        private int keepRecentMessages = 6;
        private int summaryMaxTokens = 2048;

        private Builder(LlmClient llm, String model) {
            this.llm = llm;
            this.model = model;
        }

        public Builder estimator(TokenEstimator estimator) {
            this.estimator = Objects.requireNonNull(estimator, "estimator");
            return this;
        }

        /** Compact once the estimated history exceeds this many tokens. */
        public Builder triggerTokens(int triggerTokens) {
            if (triggerTokens <= 0) {
                throw new IllegalArgumentException("triggerTokens must be > 0");
            }
            this.triggerTokens = triggerTokens;
            return this;
        }

        /** Keep this many most-recent messages verbatim (never summarised). */
        public Builder keepRecentMessages(int keepRecentMessages) {
            if (keepRecentMessages < 0) {
                throw new IllegalArgumentException("keepRecentMessages must be >= 0");
            }
            this.keepRecentMessages = keepRecentMessages;
            return this;
        }

        public Builder summaryMaxTokens(int summaryMaxTokens) {
            if (summaryMaxTokens <= 0) {
                throw new IllegalArgumentException("summaryMaxTokens must be > 0");
            }
            this.summaryMaxTokens = summaryMaxTokens;
            return this;
        }

        public SummarizingCompactor build() {
            return new SummarizingCompactor(this);
        }
    }
}
