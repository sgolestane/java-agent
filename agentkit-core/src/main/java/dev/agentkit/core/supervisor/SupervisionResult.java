package dev.agentkit.core.supervisor;

import dev.agentkit.core.llm.TokenUsage;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a supervised run: the synthesized final output plus the
 * per-subagent outcomes and aggregated cost.
 *
 * @param output      the synthesized answer to the original goal; never
 *                    {@code null}
 * @param outcomes    each subagent's outcome, in task-submission order;
 *                    unmodifiable
 * @param totalSteps  total reasoning/tool steps across all subagents (the
 *                    synthesis call is not counted)
 * @param totalUsage  cumulative token usage across all subagents (the synthesis
 *                    call is not counted); never {@code null}
 */
public record SupervisionResult(String output, List<SubagentOutcome> outcomes,
                                int totalSteps, TokenUsage totalUsage) {

    public SupervisionResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(outcomes, "outcomes");
        Objects.requireNonNull(totalUsage, "totalUsage");
        outcomes = List.copyOf(outcomes);
        if (totalSteps < 0) {
            throw new IllegalArgumentException("totalSteps must be >= 0, was " + totalSteps);
        }
    }

    /** Whether every delegated subagent completed successfully. */
    public boolean allSucceeded() {
        return outcomes.stream().allMatch(SubagentOutcome::succeeded);
    }

    /** Outcomes whose delegated run did not complete successfully. */
    public List<SubagentOutcome> failures() {
        return outcomes.stream().filter(o -> !o.succeeded()).toList();
    }
}
