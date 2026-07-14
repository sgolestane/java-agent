package dev.agentkit.examples;

import dev.agentkit.anthropic.AnthropicLlmClient;
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
 *   <li>{@code AGENTKIT_BACKEND=bedrock} — the <b>Mantle</b> backend (standard AWS
 *       credential chain, {@code AWS_REGION}), model {@code anthropic.claude-opus-4-6}
 *       routed via a Bedrock project. Invocation needs
 *       {@code bedrock-mantle:CreateInference}.</li>
 *   <li>{@code AGENTKIT_BEDROCK_INVOKE_MODEL=true} — the classic <b>InvokeModel</b>
 *       backend ({@code bedrock:InvokeModel}) instead, model
 *       {@code us.anthropic.claude-opus-4-6} (a cross-region inference profile).</li>
 *   <li>{@code AGENTKIT_BEDROCK_DISCOVER_PROFILES=true} — implies InvokeModel and
 *       additionally discovers your <em>application inference profiles</em>,
 *       resolving the logical model id to your profile ARN at runtime.</li>
 * </ul>
 *
 * @param llm   the model client
 * @param model the model id to put on {@code AgentConfig} for this backend
 */
public record ExampleBackend(LlmClient llm, String model) {

    /** Chooses the backend from {@code AGENTKIT_BACKEND} (and related) env vars. */
    public static ExampleBackend fromEnv() {
        if (!"bedrock".equalsIgnoreCase(System.getenv("AGENTKIT_BACKEND"))) {
            return new ExampleBackend(AnthropicLlmClient.fromEnv(), AnthropicLlmClient.DEFAULT_MODEL);
        }

        // Application inference profiles only exist on the InvokeModel path, so
        // discovery implies it.
        boolean discover = "true".equalsIgnoreCase(System.getenv("AGENTKIT_BEDROCK_DISCOVER_PROFILES"));
        boolean invokeModel = discover
                || "true".equalsIgnoreCase(System.getenv("AGENTKIT_BEDROCK_INVOKE_MODEL"));

        if (invokeModel) {
            if (discover) {
                // A one-off control-plane call maps logical ids → your profile ARNs.
                // The AgentConfig model is the bare foundation-model id (a resolver key).
                try (BedrockClient control = BedrockClient.create()) {
                    return new ExampleBackend(
                            Bedrock.invokeModel(InferenceProfiles.resolver(control)),
                            BedrockModels.CLAUDE_OPUS_4_6);
                }
            }
            // No discovery: invoke the cross-region inference-profile id directly
            // (the bare foundation-model id is not on-demand invokable via InvokeModel).
            return new ExampleBackend(Bedrock.invokeModel(), BedrockModels.US_CLAUDE_OPUS_4_6);
        }

        // Mantle (default): the bare foundation-model id, routed via the project.
        return new ExampleBackend(Bedrock.llmClient(), BedrockModels.CLAUDE_OPUS_4_6);
    }
}
