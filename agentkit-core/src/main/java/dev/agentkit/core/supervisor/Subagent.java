package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A named, specialised worker a {@link Supervisor} can delegate a subgoal to.
 *
 * <p>A subagent pairs an {@link Agent} with an identity: a stable {@code name}
 * the supervisor routes by, and a one-line {@code description} of what it is good
 * at (shown to an LLM supervisor so it can choose). Each delegation runs a
 * <em>fresh</em> agent obtained from the {@link Supplier}, so per-run stateful
 * collaborators (a {@code DisclosingToolRegistry} that accumulates revealed
 * tools, a {@code WorkingMemory} scratchpad) never leak between delegations or
 * across parallel fan-out — the same reason {@code SelfVerifyingAgent} takes a
 * supplier.
 */
public final class Subagent {

    private final String name;
    private final String description;
    private final Supplier<Agent> agentFactory;

    private Subagent(String name, String description, Supplier<Agent> agentFactory) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Subagent name must not be blank");
        }
    }

    /** A subagent that builds a fresh {@link Agent} for each delegation (preferred). */
    public static Subagent of(String name, String description, Supplier<Agent> agentFactory) {
        return new Subagent(name, description, agentFactory);
    }

    /**
     * Convenience for a stateless {@link Agent} that is safe to reuse across
     * delegations. Prefer {@link #of(String, String, Supplier)} when any
     * collaborator carries per-run state or the subagent may run in parallel with
     * itself — a single {@code Agent} shared across threads is not guaranteed safe.
     */
    public static Subagent of(String name, String description, Agent agent) {
        Objects.requireNonNull(agent, "agent");
        return new Subagent(name, description, () -> agent);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Runs a fresh agent toward {@code subgoal}. */
    public AgentResult handle(Goal subgoal) {
        Objects.requireNonNull(subgoal, "subgoal");
        return agentFactory.get().run(subgoal);
    }
}
