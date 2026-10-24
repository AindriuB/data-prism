package io.github.aindriub.dataprism.security;

import java.util.Objects;
import java.util.Set;

/**
 * Optionally restricts which client ids may call at all. Matching is exact and
 * case-sensitive; nothing is trimmed or normalised.
 */
public final class ClientAllowlist {

    private static final ClientAllowlist UNRESTRICTED = new ClientAllowlist(null);

    private final Set<String> allowed;

    private ClientAllowlist(Set<String> allowed) {
        this.allowed = allowed;
    }

    /** Permits every client id. */
    public static ClientAllowlist unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * @throws IllegalArgumentException when {@code clientIds} is empty or holds a blank entry
     */
    public static ClientAllowlist of(Set<String> clientIds) {
        Objects.requireNonNull(clientIds, "clientIds");
        if (clientIds.isEmpty()) {
            throw new IllegalArgumentException("clientIds must not be empty");
        }
        for (String id : clientIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("clientIds must not contain a blank entry");
            }
        }
        return new ClientAllowlist(Set.copyOf(clientIds));
    }

    public boolean permits(String clientId) {
        return allowed == null || (clientId != null && allowed.contains(clientId));
    }
}
