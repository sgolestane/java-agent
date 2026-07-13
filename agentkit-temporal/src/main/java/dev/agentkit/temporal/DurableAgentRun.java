package dev.agentkit.temporal;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Objects;

/**
 * The complete, serializable input to an {@link AgentWorkflow}: the goal, the run
 * configuration, the advertised tool specs, and the activity tuning.
 *
 * <p>This is the durable analogue of constructing an in-process {@code Agent}
 * from {@code (config, tools)} and calling {@code run(goal)} — but the model
 * client and the tool implementations live in the worker's activity
 * implementations, not here. The workflow holds only what it can replay
 * deterministically.
 *
 * @param goal    the objective to pursue
 * @param config  the model/step configuration (its {@code maxSteps} bounds the loop)
 * @param tools   the tool specs advertised to the model each turn
 * @param options activity timeouts and retry limits
 */
public record DurableAgentRun(Goal goal, AgentConfig config, List<ToolSpec> tools,
                              DurableAgentOptions options) {

    public DurableAgentRun {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(options, "options");
        tools = List.copyOf(tools);
    }

    /** Builds a run with default activity options and no tools. */
    public static DurableAgentRun of(Goal goal, AgentConfig config) {
        return new DurableAgentRun(goal, config, List.of(), DurableAgentOptions.defaults());
    }

    /** Builds a run with default activity options. */
    public static DurableAgentRun of(Goal goal, AgentConfig config, List<ToolSpec> tools) {
        return new DurableAgentRun(goal, config, tools, DurableAgentOptions.defaults());
    }
}
