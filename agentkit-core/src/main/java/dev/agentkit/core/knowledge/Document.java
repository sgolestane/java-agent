package dev.agentkit.core.knowledge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A unit of source content ingested into a {@link KnowledgeBase}.
 *
 * @param id       unique document id; never {@code null} or blank
 * @param text     the document body; never {@code null}
 * @param metadata arbitrary application metadata (e.g. title, url, source);
 *                 never {@code null}, stored as a defensive unmodifiable copy
 */
public record Document(String id, String text, Map<String, Object> metadata) {

    public Document {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Document id must not be blank");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static Document of(String id, String text) {
        return new Document(id, text, Map.of());
    }
}
