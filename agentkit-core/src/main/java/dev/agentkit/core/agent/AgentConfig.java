package dev.agentkit.core.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for an {@link Agent} run.
 *
 * @param model        the model identifier passed to the {@code LlmClient}
 * @param systemPrompt the system prompt, or {@code null} for none
 * @param maxSteps     the maximum number of model turns before the loop stops
 * @param maxTokens    the per-turn output token limit
 * @param options      provider-specific options forwarded on every request
 */
public record AgentConfig(String model, String systemPrompt, int maxSteps, int maxTokens,
                          Map<String, Object> options) {

    public AgentConfig {
        Objects.requireNonNull(model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        Objects.requireNonNull(options, "options");
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    public Optional<String> systemPromptValue() {
        return Optional.ofNullable(systemPrompt);
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    /** Builder for {@link AgentConfig}. */
    public static final class Builder {
        private final String model;
        private String systemPrompt;
        private int maxSteps = 10;
        private int maxTokens = 4096;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder(String model) {
            this.model = model;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder option(String key, Object value) {
            this.options.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(model, systemPrompt, maxSteps, maxTokens, options);
        }
    }
}
