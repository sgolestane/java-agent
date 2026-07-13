package dev.agentkit.bedrock;

import com.anthropic.bedrock.backends.BedrockMantleBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.anthropic.ModelResolver;
import java.util.Objects;
import software.amazon.awssdk.regions.Region;

/**
 * Factory for running the AgentKit Anthropic adapter against Claude on Amazon
 * Bedrock. It wires the Anthropic SDK's Bedrock (Mantle) backend into an
 * {@link AnthropicLlmClient}; the rest of AgentKit is unchanged.
 *
 * <p>Model ids on Bedrock differ from the first-party API — foundation-model
 * ids carry an {@code anthropic.} prefix (see {@link BedrockModels}), and
 * application inference profiles are account-specific ARNs. Pass a
 * {@link ModelResolver} to map the logical model ids your agents use onto the
 * concrete wire ids; {@link InferenceProfiles} can build one by discovering your
 * application inference profiles at runtime.
 *
 * <pre>{@code
 * // Cross-region / foundation-model ids — set the model on AgentConfig directly:
 * LlmClient llm = Bedrock.llmClient();               // AWS_REGION + default cred chain
 * agent.run(...); // AgentConfig model = "anthropic.claude-opus-4-8"
 *
 * // Application inference profiles — discover ARNs and map logical ids to them:
 * try (BedrockClient control = BedrockClient.create()) {
 *     ModelResolver resolver = InferenceProfiles.resolver(control);
 *     LlmClient llm2 = Bedrock.llmClient(resolver);  // AgentConfig model = "anthropic.claude-opus-4-8"
 * }                                                  // → resolved to your profile ARN per request
 * }</pre>
 *
 * <p>AWS credentials and region resolve through the standard AWS chain
 * ({@code AWS_REGION} is required); use {@link #llmClient(Region, ModelResolver)}
 * or build a {@link BedrockMantleBackend} yourself to set them explicitly.
 */
public final class Bedrock {

    private Bedrock() {
    }

    /** An {@link AnthropicClient} over {@code backend} (the Messages API data plane). */
    public static AnthropicClient client(BedrockMantleBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return AnthropicOkHttpClient.builder().backend(backend).build();
    }

    /** A Bedrock-backed {@code LlmClient} using the default region/credentials, no model mapping. */
    public static AnthropicLlmClient llmClient() {
        return llmClient(ModelResolver.IDENTITY);
    }

    /** A Bedrock-backed {@code LlmClient} using the default region/credentials and {@code modelResolver}. */
    public static AnthropicLlmClient llmClient(ModelResolver modelResolver) {
        return llmClient(BedrockMantleBackend.fromEnv(), modelResolver);
    }

    /** A Bedrock-backed {@code LlmClient} pinned to {@code region}, with {@code modelResolver}. */
    public static AnthropicLlmClient llmClient(Region region, ModelResolver modelResolver) {
        Objects.requireNonNull(region, "region");
        return llmClient(BedrockMantleBackend.builder().region(region).build(), modelResolver);
    }

    /** A Bedrock-backed {@code LlmClient} over an explicitly-built {@code backend}. */
    public static AnthropicLlmClient llmClient(BedrockMantleBackend backend, ModelResolver modelResolver) {
        return new AnthropicLlmClient(client(backend), modelResolver);
    }
}
