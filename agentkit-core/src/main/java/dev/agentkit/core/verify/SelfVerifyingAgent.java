package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an {@link Agent} with a verify-and-retry loop: after the agent produces a
 * result, a {@link Verifier} checks it; on failure the agent is re-run with the
 * verifier's feedback appended to the goal, up to {@code maxAttempts} times.
 *
 * <p>This is the reliability backbone for unsupervised runs: the agent does not
 * get to declare success unilaterally. If the final attempt still fails
 * verification, the result is returned with {@link StopReason#VERIFICATION_FAILED}
 * rather than {@code COMPLETED}, so callers can tell a verified success from an
 * unverified one.
 */
public final class SelfVerifyingAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfVerifyingAgent.class);

    private final Agent agent;
    private final Verifier verifier;
    private final int maxAttempts;

    public SelfVerifyingAgent(Agent agent, Verifier verifier, int maxAttempts) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /** Runs the agent toward {@code goal}, verifying (and retrying on failure). */
    public AgentResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        Goal current = goal;
        AgentResult last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = agent.run(current);
            if (!last.isSuccess()) {
                return last; // the agent itself stopped/failed — nothing to verify
            }

            Verdict verdict = verifier.verify(goal, last.output());
            if (verdict.passed()) {
                log.debug("Verification passed on attempt {}", attempt);
                return last;
            }
            log.info("Verification failed on attempt {}/{}: {}", attempt, maxAttempts, verdict.feedback());

            current = new Goal(goal.description()
                    + "\n\nA previous attempt was rejected during verification with this feedback:\n"
                    + verdict.feedback()
                    + "\n\nProduce a corrected result that addresses it.",
                    goal.parameters());
        }

        // Exhausted attempts without passing verification.
        return AgentResult.stopped(StopReason.VERIFICATION_FAILED, last.output(), last.steps(), last.usage());
    }
}
