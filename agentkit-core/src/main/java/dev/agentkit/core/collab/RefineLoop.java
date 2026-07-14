package dev.agentkit.core.collab;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A generator&#8596;critic collaboration: one agent drafts an answer, a peer
 * {@link Critic} reviews it, and the generator revises against the feedback —
 * repeating until the critic approves or the round cap is reached.
 *
 * <p>Each round builds a <em>fresh</em> generator {@link Agent} from a
 * {@link Supplier}, so a revision doesn't inherit the previous run's tool state;
 * the previous draft and the reviewer's feedback are threaded in through the
 * goal. This is peer collaboration rather than self-review — the critic can be a
 * separate model call or a whole peer agent (see {@link Critics}).
 *
 * <p>The loop stops early and returns the failing draft if a generator round does
 * not complete successfully, so an unproductive run never spins to the cap.
 */
public final class RefineLoop {

    private static final Logger log = LoggerFactory.getLogger(RefineLoop.class);

    private final Supplier<Agent> generatorFactory;
    private final Critic critic;
    private final int maxRounds;

    /**
     * @param generatorFactory builds a fresh generator agent for each round
     * @param critic           reviews each draft
     * @param maxRounds        the maximum number of generator rounds (>= 1); the
     *                         initial draft counts as round 1
     */
    public RefineLoop(Supplier<Agent> generatorFactory, Critic critic, int maxRounds) {
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory");
        this.critic = Objects.requireNonNull(critic, "critic");
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1");
        }
        this.maxRounds = maxRounds;
    }

    /** Runs the loop for {@code goal} and returns the refined outcome. */
    public RefineResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        String draft = "";
        String feedback = "";

        for (int round = 1; round <= maxRounds; round++) {
            Goal roundGoal = round == 1 ? goal : revisionGoal(goal, draft, feedback);
            AgentResult result = generatorFactory.get().run(roundGoal);
            if (!result.isSuccess()) {
                log.info("Generator did not complete on round {}/{}: {}", round, maxRounds, result.stopReason());
                return new RefineResult(result.output(), false, round, feedback);
            }
            draft = result.output();

            Critique critique = critic.review(goal, draft);
            if (critique.approved()) {
                return new RefineResult(draft, true, round, "");
            }
            feedback = critique.feedback();
            log.info("Critic requested a revision on round {}/{}", round, maxRounds);
        }
        // Round cap reached without approval — return the best draft so far.
        return new RefineResult(draft, false, maxRounds, feedback);
    }

    private static Goal revisionGoal(Goal goal, String previousDraft, String feedback) {
        return Goal.of("Revise your previous draft to address the reviewer's feedback.\n\n"
                + "GOAL:\n" + goal.description() + "\n\n"
                + "YOUR PREVIOUS DRAFT:\n" + previousDraft + "\n\n"
                + "REVIEWER FEEDBACK:\n" + feedback);
    }
}
