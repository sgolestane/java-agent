package dev.agentkit.examples;

import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.anthropic.ModelResolver;
import dev.agentkit.bedrock.Bedrock;
import dev.agentkit.bedrock.BedrockModels;
import dev.agentkit.bedrock.InferenceProfiles;
import dev.agentkit.core.llm.LlmClient;
import software.amazon.awssdk.services.bedrock.BedrockClient;

/**
 * Selects the model backend for the example {@code main} methods from the
 * environment, so the same demos run against either the first-party Anthropic
 * API or Claude on Amazon Bedrock without code changes.
 *
 * <ul>
 *   <li>Default — the first-party API ({@code ANTHROPIC_API_KEY}), model
 *       {@code claude-opus-4-8}.</li>
 *   <li>{@code AGENTKIT_BACKEND=bedrock} — Bedrock (standard AWS credential chain,
 *       {@code AWS_REGION}), model {@code anthropic.claude-opus-4-8}.</li>
 *   <li>{@code AGENTKIT_BEDROCK_DISCOVER_PROFILES=true} — additionally discover
 *       application inference profiles and resolve the model id to your profile
 *       ARN at runtime.</li>
 * </ul>
 *
 * @param llm   the model client
 * @param model the model id to put on {@code AgentConfig} for this backend
 */
public record ExampleBackend(LlmClient llm, String model) {

    /** Chooses the backend from {@code AGENTKIT_BACKEND} (and related) env vars. */
    public static ExampleBackend fromEnv() {
        if ("bedrock".equalsIgnoreCase(System.getenv("AGENTKIT_BACKEND"))) {
            ModelResolver resolver = ModelResolver.IDENTITY;
            if ("true".equalsIgnoreCase(System.getenv("AGENTKIT_BEDROCK_DISCOVER_PROFILES"))) {
                // A one-off control-plane call maps logical ids → your profile ARNs.
                try (BedrockClient control = BedrockClient.create()) {
                    resolver = InferenceProfiles.resolver(control);
                }
            }
            return new ExampleBackend(Bedrock.llmClient(resolver), BedrockModels.CLAUDE_OPUS_4_8);
        }
        return new ExampleBackend(AnthropicLlmClient.fromEnv(), AnthropicLlmClient.DEFAULT_MODEL);
    }
}
