package dev.agentkit.core.knowledge;

import java.util.Collection;
import java.util.List;

/**
 * The agent's grounding in application-owned information: ingest documents, then
 * retrieve the most relevant chunks for a query.
 *
 * <p>AgentKit supplies the <em>mechanism</em> — chunking, lexical and vector
 * retrievers, and a search tool — while the application supplies the data (and,
 * for vector search, an {@link EmbeddingModel}).
 */
public interface KnowledgeBase {

    /** Ingests a document (chunking and indexing it). */
    void ingest(Document document);

    /** Ingests several documents. */
    default void ingestAll(Collection<Document> documents) {
        documents.forEach(this::ingest);
    }

    /** Returns up to {@code maxResults} relevant chunks, most relevant first. */
    List<SearchResult> search(String query, int maxResults);

    /** The number of indexed chunks. */
    int chunkCount();
}
