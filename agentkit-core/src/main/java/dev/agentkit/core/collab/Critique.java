package dev.agentkit.core.collab;

import java.util.Objects;

/**
 * A peer's assessment of a draft: whether it is good enough, and if not, what to
 * fix. Consumed by {@link RefineLoop} to decide whether to accept the draft or
 * send it back for another revision.
 *
 * @param approved {@code true} if the draft is acceptable as-is
 * @param feedback actionable guidance for the next revision; empty when approved
 */
public record Critique(boolean approved, String feedback) {

    public Critique {
        Objects.requireNonNull(feedback, "feedback");
    }

    /** An approving critique with no further feedback. */
    public static Critique approve() {
        return new Critique(true, "");
    }

    /** A revise-requested critique carrying actionable {@code feedback}. */
    public static Critique revise(String feedback) {
        Objects.requireNonNull(feedback, "feedback");
        return new Critique(false, feedback);
    }
}
