package dev.agentkit.core.supervisor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered set of {@link Subagent}s a {@link Supervisor} can delegate to,
 * keyed by name.
 *
 * <p>Insertion order is preserved so the delegation catalog and any advertised
 * tool schema are stable across runs (which keeps prompt caches warm). Names are
 * unique: registering a second subagent under an existing name is rejected
 * rather than silently overwriting a routing target.
 */
public final class SubagentRoster {

    private final Map<String, Subagent> byName = new LinkedHashMap<>();

    /** Registers {@code subagent}; returns {@code this} for chaining. */
    public SubagentRoster add(Subagent subagent) {
        Objects.requireNonNull(subagent, "subagent");
        if (byName.putIfAbsent(subagent.name(), subagent) != null) {
            throw new IllegalArgumentException("Duplicate subagent name: '" + subagent.name() + "'");
        }
        return this;
    }

    /** Looks up a subagent by name. */
    public Optional<Subagent> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All subagents, in registration order. */
    public List<Subagent> all() {
        return List.copyOf(byName.values());
    }

    /** All subagent names, in registration order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Renders a {@code name: description} catalog, one subagent per line, for
     * inclusion in a supervisor's system prompt.
     */
    public String catalog() {
        StringBuilder sb = new StringBuilder();
        for (Subagent subagent : byName.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(subagent.name()).append(": ").append(subagent.description());
        }
        return sb.toString();
    }

    public static SubagentRoster of(Subagent... subagents) {
        SubagentRoster roster = new SubagentRoster();
        for (Subagent subagent : subagents) {
            roster.add(subagent);
        }
        return roster;
    }
}
