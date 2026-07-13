package dev.agentkit.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.message.ContentBlock;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;

/**
 * Builds the Temporal {@link DataConverter} the durable agent uses on both the
 * client and the worker.
 *
 * <p>It starts from Temporal's default JSON object mapper (records, {@code
 * Optional}, and java.time are already handled) and adds the one thing the
 * default cannot infer: polymorphic (de)serialization of the sealed {@code
 * ContentBlock} hierarchy, via {@link ContentBlockMixin}. Keeping this here means
 * the core message types carry no serialization annotations.
 *
 * <p><strong>Both ends must share this converter.</strong> Configure the
 * {@code WorkflowClient} with it (so inputs/results serialize) and the worker's
 * client too (so activities see the same payloads); a mismatch surfaces as a
 * deserialization error at the boundary.
 */
public final class DurableJson {

    private DurableJson() {
    }

    /** The object mapper used by the durable converter, with ContentBlock wired up. */
    public static ObjectMapper objectMapper() {
        ObjectMapper mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper();
        mapper.addMixIn(ContentBlock.class, ContentBlockMixin.class);
        return mapper;
    }

    /** A data converter that can round-trip every type crossing the agent boundary. */
    public static DataConverter dataConverter() {
        return DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(objectMapper()));
    }
}
