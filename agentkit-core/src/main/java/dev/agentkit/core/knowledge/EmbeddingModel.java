package dev.agentkit.core.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns text into a dense vector for semantic retrieval.
 *
 * <p>The framework provides the retrieval <em>mechanism</em> ({@link VectorRetriever})
 * but not an embedding provider — supply one by implementing this interface over
 * your model of choice. All vectors returned by a given model must share the same
 * dimensionality.
 */
@FunctionalInterface
public interface EmbeddingModel {

    /** Embeds a single text into a fixed-length vector. */
    float[] embed(String text);

    /** Embeds several texts; override for batched/more-efficient implementations. */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
