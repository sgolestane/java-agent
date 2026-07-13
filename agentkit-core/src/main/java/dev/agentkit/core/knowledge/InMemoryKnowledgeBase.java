package dev.agentkit.core.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * A {@link KnowledgeBase} that chunks with a {@link Chunker} and ranks with a
 * {@link Retriever}, both pluggable. In-memory and not thread-safe: ingest during
 * setup, then share read-only for the run.
 */
public final class InMemoryKnowledgeBase implements KnowledgeBase {

    /** A sensible default chunker: ~120-word windows overlapping by 20 words. */
    public static final Chunker DEFAULT_CHUNKER = Chunkers.slidingWindow(120, 20);

    private final Chunker chunker;
    private final Retriever retriever;

    public InMemoryKnowledgeBase(Chunker chunker, Retriever retriever) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
    }

    /** A BM25 (lexical) knowledge base with the default chunker. */
    public static InMemoryKnowledgeBase bm25() {
        return new InMemoryKnowledgeBase(DEFAULT_CHUNKER, new Bm25Retriever());
    }

    /** A vector (semantic) knowledge base with the default chunker. */
    public static InMemoryKnowledgeBase vector(EmbeddingModel model) {
        return new InMemoryKnowledgeBase(DEFAULT_CHUNKER, new VectorRetriever(model));
    }

    @Override
    public void ingest(Document document) {
        Objects.requireNonNull(document, "document");
        retriever.add(chunker.chunk(document));
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        Objects.requireNonNull(query, "query");
        return retriever.search(query, maxResults);
    }

    @Override
    public int chunkCount() {
        return retriever.size();
    }
}
