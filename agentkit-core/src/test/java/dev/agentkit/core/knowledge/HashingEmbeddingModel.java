package dev.agentkit.core.knowledge;

import dev.agentkit.core.retrieval.Bm25Index;

/**
 * A deterministic bag-of-words embedding model for tests: each token increments a
 * dimension chosen by its hash. Not semantically meaningful, but stable and
 * dependency-free — enough to exercise cosine ranking and dimension checks.
 */
final class HashingEmbeddingModel implements EmbeddingModel {

    private final int dim;

    HashingEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        for (String token : Bm25Index.tokenize(text)) {
            int bucket = Math.floorMod(token.hashCode(), dim);
            v[bucket] += 1f;
        }
        return v;
    }
}
