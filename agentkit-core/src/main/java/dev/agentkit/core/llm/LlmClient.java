package dev.agentkit.core.llm;

/**
 * The single seam between AgentKit and a concrete LLM provider.
 *
 * <p>The core depends only on this interface, so the agent loop, tools, skills,
 * memory, and orchestration are all testable with a fake client and portable
 * across providers. Adapters (e.g. the Anthropic module) translate a
 * {@link LlmRequest} into their SDK's request, invoke the model, and normalise
 * the response into an {@link LlmResponse}.
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * Executes a single model turn.
     *
     * @param request the request; never {@code null}
     * @return the normalised response; never {@code null}
     * @throws LlmException if the call fails
     */
    LlmResponse generate(LlmRequest request);
}
