package dev.agentkit.core.collab;

import java.util.Objects;

/**
 * The outcome of a {@link RefineLoop}.
 *
 * @param output       the final draft
 * @param approved     whether the critic approved that draft
 * @param rounds       how many generator rounds ran (at least 1)
 * @param lastFeedback the most recent critic feedback (empty if approved)
 */
public record RefineResult(String output, boolean approved, int rounds, String lastFeedback) {

    public RefineResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(lastFeedback, "lastFeedback");
    }
}
