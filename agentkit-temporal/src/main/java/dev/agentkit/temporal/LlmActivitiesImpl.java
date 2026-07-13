package dev.agentkit.temporal;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import java.util.Objects;

/**
 * Worker-side {@link LlmActivities} implementation that delegates to a core
 * {@link LlmClient}. It rebuilds an {@link LlmRequest} from the serialized
 * {@link LlmCallSpec} and maps the response into a converter-friendly
 * {@link LlmTurn}.
 *
 * <p>This runs outside the workflow sandbox, so a real network call to a model
 * provider is legitimate here. A thrown {@link RuntimeException} (e.g. a rate
 * limit) propagates to Temporal, which retries the activity per its options.
 */
public final class LlmActivitiesImpl implements LlmActivities {

    private final LlmClient llm;

    public LlmActivitiesImpl(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public LlmTurn generate(LlmCallSpec spec) {
        LlmRequest.Builder builder = LlmRequest.builder(spec.model())
                .messages(spec.conversation())
                .tools(spec.tools())
                .maxTokens(spec.maxTokens());
        if (spec.system() != null) {
            builder.system(spec.system());
        }
        spec.options().forEach(builder::option);

        LlmResponse response = llm.generate(builder.build());
        return new LlmTurn(response.message(), response.stopReason(), response.usage());
    }
}
