package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Goal;

/**
 * Checks whether an agent's output actually satisfies its goal — the core of
 * "don't trust the first answer" for unsupervised agents. A verifier can be a
 * rule check, a schema validation, or an independent model acting as a critic.
 */
@FunctionalInterface
public interface Verifier {

    /** A verifier that accepts everything (no verification). */
    Verifier ALWAYS_PASS = (goal, output) -> Verdict.pass();

    /** Verifies {@code output} against {@code goal}. */
    Verdict verify(Goal goal, String output);
}
