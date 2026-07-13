package dev.agentkit.core.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The objective handed to an agent.
 *
 * <p>A goal is the primary input to an unsupervised agent run: a natural-language
 * description of what to achieve, plus optional structured parameters that the
 * application wants to make available (identifiers, constraints, references).
 *
 * @param description human-readable statement of what to achieve; never
 *                    {@code null} or blank
 * @param parameters structured, application-supplied context; never
 *                   {@code null}. Stored as a defensive, unmodifiable copy. The
 *                   copy is <em>shallow</em>: nested mutable values are shared.
 */
public record Goal(String description, Map<String, Object> parameters) {

    public Goal {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Goal description must not be blank");
        }
        Objects.requireNonNull(parameters, "parameters");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public static Goal of(String description) {
        return new Goal(description, Map.of());
    }
}
