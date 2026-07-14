package dev.agentkit.core.collab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered directory of {@link Peer}s that can collaborate, keyed by name.
 *
 * <p>Insertion order is preserved so the addressable-peer catalog and any
 * advertised tool schema are stable across runs (which keeps prompt caches warm).
 * Names are unique: registering a second peer under an existing name is rejected
 * rather than silently overwriting an addressable target.
 */
public final class PeerGroup {

    private final Map<String, Peer> byName = new LinkedHashMap<>();

    /** Registers {@code peer}; returns {@code this} for chaining. */
    public PeerGroup add(Peer peer) {
        Objects.requireNonNull(peer, "peer");
        if (byName.putIfAbsent(peer.name(), peer) != null) {
            throw new IllegalArgumentException("Duplicate peer name: '" + peer.name() + "'");
        }
        return this;
    }

    /** Looks up a peer by name. */
    public Optional<Peer> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All peers, in registration order. */
    public List<Peer> all() {
        return List.copyOf(byName.values());
    }

    /** All peer names, in registration order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Renders a {@code name: description} catalog, one peer per line, for inclusion
     * in an agent's system prompt or a messaging tool description.
     */
    public String catalog() {
        StringBuilder sb = new StringBuilder();
        for (Peer peer : byName.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(peer.name()).append(": ").append(peer.description());
        }
        return sb.toString();
    }

    public static PeerGroup of(Peer... peers) {
        PeerGroup group = new PeerGroup();
        for (Peer peer : peers) {
            group.add(peer);
        }
        return group;
    }
}
