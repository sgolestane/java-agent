package dev.agentkit.core.knowledge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A retrievable fragment of a {@link Document}.
 *
 * @param id         unique chunk id; never {@code null} or blank
 * @param documentId the id of the source document; never {@code null}
 * @param text       the chunk text; never {@code null}
 * @param metadata   metadata inherited from the document plus chunk-specific
 *                   fields (e.g. ordinal); never {@code null}
 */
public record Chunk(String id, String documentId, String text, Map<String, Object> metadata) {

    public Chunk {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Chunk id must not be blank");
        }
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
