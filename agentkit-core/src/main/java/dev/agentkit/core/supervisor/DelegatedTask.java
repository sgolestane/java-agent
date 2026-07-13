package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Goal;
import java.util.Objects;

/**
 * An assignment of one subgoal to a named subagent — the unit of work a
 * {@link Supervisor} fans out.
 *
 * @param subagent the name of the {@link Subagent} to run this subgoal; must
 *                 resolve in the supervisor's roster
 * @param goal     the subgoal to pursue
 */
public record DelegatedTask(String subagent, Goal goal) {

    public DelegatedTask {
        Objects.requireNonNull(subagent, "subagent");
        Objects.requireNonNull(goal, "goal");
    }

    public static DelegatedTask of(String subagent, Goal goal) {
        return new DelegatedTask(subagent, goal);
    }

    public static DelegatedTask of(String subagent, String goalDescription) {
        return new DelegatedTask(subagent, Goal.of(goalDescription));
    }
}
