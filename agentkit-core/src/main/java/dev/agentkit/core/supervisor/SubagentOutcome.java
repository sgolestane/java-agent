package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import java.util.Objects;

/**
 * The result of running one {@link DelegatedTask}: which subagent ran, the
 * subgoal it pursued, and the {@link AgentResult} it produced.
 *
 * @param subagentName the name of the subagent that ran
 * @param goal         the subgoal it pursued
 * @param result       the outcome of the run; never {@code null}
 */
public record SubagentOutcome(String subagentName, Goal goal, AgentResult result) {

    public SubagentOutcome {
        Objects.requireNonNull(subagentName, "subagentName");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(result, "result");
    }

    /** Whether the delegated run completed successfully. */
    public boolean succeeded() {
        return result.isSuccess();
    }
}
