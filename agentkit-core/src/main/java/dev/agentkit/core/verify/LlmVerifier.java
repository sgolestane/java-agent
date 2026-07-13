package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.Message;
import java.util.Objects;

/**
 * A {@link Verifier} that uses an independent model call as a critic: it judges
 * the output against the goal and returns a pass/fail verdict with feedback.
 *
 * <p>Using a separate call (ideally fresh context) is more reliable than asking
 * the same loop to self-assess. The critic is instructed to answer {@code PASS}
 * or {@code FAIL} on the first line; anything not clearly {@code PASS} is treated
 * as a failure (fail-closed).
 */
public final class LlmVerifier implements Verifier {

    private static final String SYSTEM = """
            You are a strict verifier. Given a GOAL and a candidate OUTPUT, decide \
            whether the output fully satisfies the goal. Answer with exactly PASS \
            or FAIL on the first line. If FAIL, add a second line with specific, \
            actionable feedback on what to fix.""";

    private final LlmClient llm;
    private final String model;
    private final int maxTokens;

    public LlmVerifier(LlmClient llm, String model) {
        this(llm, model, 1024);
    }

    public LlmVerifier(LlmClient llm, String model, int maxTokens) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        this.maxTokens = maxTokens;
    }

    @Override
    public Verdict verify(Goal goal, String output) {
        String prompt = "GOAL:\n" + goal.description() + "\n\nOUTPUT:\n" + output;
        LlmRequest request = LlmRequest.builder(model)
                .system(SYSTEM)
                .maxTokens(maxTokens)
                .addMessage(Message.user(prompt))
                .build();
        String verdictText = llm.generate(request).message().text().strip();

        // Fail-closed: the ENTIRE first line (minus surrounding punctuation) must be
        // the token PASS. "PASS is not warranted…" or "PASSABLE" therefore fail.
        String firstLine = verdictText.lines().findFirst().orElse("");
        String firstToken = firstLine.strip()
                .replaceAll("^[^A-Za-z]+", "").replaceAll("[^A-Za-z]+$", "");
        if (firstToken.equalsIgnoreCase("PASS")) {
            return Verdict.pass();
        }
        String feedback = verdictText.contains("\n")
                ? verdictText.substring(verdictText.indexOf('\n') + 1).strip()
                : "";
        return Verdict.fail(feedback.isEmpty() ? "Output did not satisfy the goal." : feedback);
    }
}
