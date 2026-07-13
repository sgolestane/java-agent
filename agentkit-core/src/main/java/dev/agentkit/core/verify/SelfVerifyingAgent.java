package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an {@link Agent} with a verify-and-retry loop: after the agent produces a
 * result, a {@link Verifier} checks it; on failure a <em>fresh</em> agent is run
 * with the verifier's feedback appended to the goal, up to {@code maxAttempts}.
 *
 * <p>This is the reliability backbone for unsupervised runs: the agent does not
 * get to declare success unilaterally. If the final attempt still fails
 * verification, the result is returned with {@link StopReason#VERIFICATION_FAILED}
 * rather than {@code COMPLETED}, so callers can tell a verified success from an
 * unverified one. Steps and token usage are aggregated across attempts (the
 * verifier's own model calls are not counted).
 *
 * <p><strong>Use a fresh agent per attempt.</strong> The constructor takes a
 * {@link Supplier} so each retry gets a clean {@link Agent} — important because
 * some collaborators are per-run stateful (e.g. a {@code DisclosingToolRegistry}
 * accumulates revealed tools). The {@code (Agent, …)} convenience constructor
 * reuses one instance and is only appropriate when every collaborator is
 * stateless across runs.
 *
 * <p>Each retry re-runs the whole loop from a clean conversation rather than
 * continuing the prior one — a deliberate tradeoff favouring a clean context over
 * reusing intermediate work.
 */
public final class SelfVerifyingAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfVerifyingAgent.class);

    private final Supplier<Agent> agentFactory;
    private final Verifier verifier;
    private final int maxAttempts;

    /** Preferred: a fresh {@link Agent} is built for each attempt. */
    public SelfVerifyingAgent(Supplier<Agent> agentFactory, Verifier verifier, int maxAttempts) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Convenience for a stateless {@link Agent} that is safe to reuse across
     * attempts. Prefer {@link #SelfVerifyingAgent(Supplier, Verifier, int)} when
     * any collaborator (tool registry, context strategy) carries per-run state.
     */
    public SelfVerifyingAgent(Agent agent, Verifier verifier, int maxAttempts) {
        this(() -> Objects.requireNonNull(agent, "agent"), verifier, maxAttempts);
    }

    /** Runs the agent toward {@code goal}, verifying (and retrying on failure). */
    public AgentResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        Goal current = goal;
        AgentResult last = null;
        int totalSteps = 0;
        TokenUsage totalUsage = TokenUsage.ZERO;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = agentFactory.get().run(current);
            totalSteps += last.steps();
            totalUsage = totalUsage.plus(last.usage());

            if (!last.isSuccess()) {
                return last; // the agent itself stopped/failed — nothing to verify
            }

            Verdict verdict = verifier.verify(goal, last.output());
            if (verdict.passed()) {
                log.debug("Verification passed on attempt {}", attempt);
                return AgentResult.completed(last.output(), totalSteps, totalUsage);
            }
            log.info("Verification failed on attempt {}/{}: {}", attempt, maxAttempts, verdict.feedback());

            current = new Goal(goal.description()
                    + "\n\nA previous attempt was rejected during verification with this feedback:\n"
                    + verdict.feedback()
                    + "\n\nProduce a corrected result that addresses it.",
                    goal.parameters());
        }

        // Exhausted attempts without passing verification.
        return AgentResult.stopped(StopReason.VERIFICATION_FAILED, last.output(), totalSteps, totalUsage);
    }
}
