package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Goal;

/**
 * Checks whether an agent's output actually satisfies its goal — the core of
 * "don't trust the first answer" for unsupervised agents. A verifier can be a
 * rule check, a schema validation, or an independent model acting as a critic.
 *
 * <p><strong>Scope.</strong> A verifier judges the agent's final output
 * <em>string</em> against the goal. It does not observe the tools the agent
 * called or the side effects they had; verifying that an action actually took
 * effect in the world (a row was written, an email was sent) is the caller's
 * responsibility and typically belongs in a tool that reads back the state. See
 * {@link Verifiers} for non-model verifiers and {@link LlmVerifier} for a
 * model-based critic.
 */
@FunctionalInterface
public interface Verifier {

    /** A verifier that accepts everything (no verification). */
    Verifier ALWAYS_PASS = (goal, output) -> Verdict.pass();

    /** Verifies {@code output} against {@code goal}. */
    Verdict verify(Goal goal, String output);
}
