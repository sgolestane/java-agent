package dev.agentkit.core.collab;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.supervisor.Subagent;
import java.util.Objects;

/**
 * {@link Critic} factories. Both a lightweight single-call critic and a full peer
 * agent are supported, and both share the same {@code APPROVE}/{@code REVISE}
 * output contract so they are interchangeable in a {@link RefineLoop}.
 *
 * <p>Parsing is fail-closed: anything whose first line is not clearly
 * {@code APPROVE} is treated as a revise request, so an unparseable critic keeps
 * the loop improving rather than accepting a draft it never actually approved.
 * The loop's round cap bounds this.
 */
public final class Critics {

    private static final String SYSTEM = """
            You are a critical reviewer collaborating with another agent to improve its work. \
            Given a GOAL and a DRAFT, decide whether the draft is good enough to accept. \
            Answer with exactly APPROVE or REVISE on the first line. If REVISE, add specific, \
            actionable feedback on the following lines describing what to change.""";

    private Critics() {
    }

    /** A critic that judges the draft with a single independent model call. */
    public static Critic llm(LlmClient llm, String model) {
        return llm(llm, model, 1024);
    }

    /** As {@link #llm(LlmClient, String)}, capping the critic's reply at {@code maxTokens}. */
    public static Critic llm(LlmClient llm, String model, int maxTokens) {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        return (goal, draft) -> {
            LlmRequest request = LlmRequest.builder(model)
                    .system(SYSTEM)
                    .maxTokens(maxTokens)
                    .addMessage(Message.user(reviewPrompt(goal, draft)))
                    .build();
            return parse(llm.generate(request).message().text());
        };
    }

    /**
     * A critic backed by a full peer agent (its own tools, memory, and loop), for
     * genuine agent-to-agent critique. The reviewer is run once per draft; a
     * reviewer run that fails is treated as a revise request carrying the reason.
     */
    public static Critic agent(Subagent reviewer) {
        Objects.requireNonNull(reviewer, "reviewer");
        return (goal, draft) -> {
            AgentResult result = reviewer.handle(Goal.of(SYSTEM + "\n\n" + reviewPrompt(goal, draft)));
            if (!result.isSuccess()) {
                return Critique.revise("Reviewer did not complete (" + result.stopReason() + ").");
            }
            return parse(result.output());
        };
    }

    private static String reviewPrompt(Goal goal, String draft) {
        return "GOAL:\n" + goal.description() + "\n\nDRAFT:\n" + draft;
    }

    /** Parses an {@code APPROVE}/{@code REVISE} reply into a {@link Critique} (fail-closed). */
    static Critique parse(String text) {
        String trimmed = text == null ? "" : text.strip();
        String firstLine = trimmed.lines().findFirst().orElse("");
        String firstToken = firstLine.strip()
                .replaceAll("^[^A-Za-z]+", "").replaceAll("[^A-Za-z]+$", "");
        if (firstToken.equalsIgnoreCase("APPROVE")) {
            return Critique.approve();
        }
        String feedback = trimmed.contains("\n")
                ? trimmed.substring(trimmed.indexOf('\n') + 1).strip()
                : "";
        return Critique.revise(feedback.isEmpty() ? "The draft did not satisfy the goal." : feedback);
    }
}
