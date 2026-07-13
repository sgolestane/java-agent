package dev.agentkit.core.knowledge;

import dev.agentkit.core.retrieval.Bm25Index;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A lexical {@link Retriever} backed by {@link Bm25Index}. Good for exact-term,
 * keyword, and code-like queries; requires no embedding model.
 *
 * <p>The BM25 index is rebuilt lazily on the first search after chunks change, so
 * batch ingestion followed by queries is efficient. Not thread-safe.
 */
public final class Bm25Retriever implements Retriever {

    private final Map<String, Chunk> chunks = new LinkedHashMap<>();
    private Bm25Index index;
    private boolean dirty = true;

    @Override
    public void add(Collection<Chunk> newChunks) {
        Objects.requireNonNull(newChunks, "newChunks");
        for (Chunk chunk : newChunks) {
            chunks.put(chunk.id(), chunk);
        }
        dirty = true;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        if (dirty) {
            rebuild();
        }
        List<SearchResult> results = new ArrayList<>();
        for (Bm25Index.Scored hit : index.search(query, maxResults)) {
            results.add(new SearchResult(chunks.get(hit.id()), hit.score()));
        }
        return results;
    }

    @Override
    public int size() {
        return chunks.size();
    }

    private void rebuild() {
        Map<String, String> corpus = new LinkedHashMap<>();
        chunks.forEach((id, chunk) -> corpus.put(id, chunk.text()));
        index = Bm25Index.of(corpus);
        dirty = false;
    }
}
