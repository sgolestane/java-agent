package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.Message;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factories for common {@link Synthesizer}s.
 */
public final class Synthesizers {

    private static final Logger log = LoggerFactory.getLogger(Synthesizers.class);

    private Synthesizers() {
    }

    /**
     * A deterministic synthesizer that concatenates each subagent's output under a
     * labelled heading, marking any failed delegation. No model call — cheap,
     * reproducible, and a safe default when the pieces don't need reconciling.
     */
    public static Synthesizer concatenating() {
        return (original, outcomes) -> {
            StringBuilder sb = new StringBuilder();
            for (SubagentOutcome outcome : outcomes) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("## ").append(outcome.subagentName());
                if (!outcome.succeeded()) {
                    sb.append(" (").append(outcome.result().stopReason()).append(')');
                }
                sb.append('\n').append(outcome.result().output());
            }
            return sb.toString();
        };
    }

    /**
     * A synthesizer that asks a model to reconcile the subagent outputs into one
     * coherent answer for the original goal. Failed delegations are surfaced to the
     * model as such so it can note gaps rather than invent content. On a model
     * failure it falls back to {@link #concatenating()} so the supervisor still
     * returns the raw pieces rather than nothing.
     */
    public static Synthesizer llm(LlmClient llm, String model) {
        return llm(llm, model, 2048);
    }

    public static Synthesizer llm(LlmClient llm, String model, int maxTokens) {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        Synthesizer fallback = concatenating();
        return (original, outcomes) -> {
            String prompt = buildPrompt(original, outcomes);
            LlmRequest request = LlmRequest.builder(model)
                    .system("You are a supervisor synthesizing the results of several subagents into a "
                            + "single, coherent answer to the original goal. Reconcile overlaps, resolve "
                            + "conflicts, and clearly note anything a failed subagent left incomplete. "
                            + "Do not invent information a subagent did not provide.")
                    .maxTokens(maxTokens)
                    .addMessage(Message.user(prompt))
                    .build();
            try {
                return llm.generate(request).message().text();
            } catch (RuntimeException e) {
                log.warn("LLM synthesis failed; falling back to concatenation", e);
                return fallback.synthesize(original, outcomes);
            }
        };
    }

    private static String buildPrompt(Goal original, List<SubagentOutcome> outcomes) {
        StringBuilder sb = new StringBuilder("ORIGINAL GOAL:\n").append(original.description());
        sb.append("\n\nSUBAGENT RESULTS:");
        for (SubagentOutcome outcome : outcomes) {
            sb.append("\n\n[").append(outcome.subagentName()).append(']');
            if (!outcome.succeeded()) {
                sb.append(" (did not complete: ").append(outcome.result().stopReason()).append(')');
            }
            sb.append('\n').append(outcome.result().output());
        }
        sb.append("\n\nProduce the final answer to the original goal.");
        return sb.toString();
    }
}
