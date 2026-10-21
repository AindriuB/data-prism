package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;

/**
 * Chooses the key a {@link Sealer} writes under (docs/plan/design/cluster-security.md 4.6, the write-key rule).
 * The member-card policy of tasks 227 and 229 implements this. When no held key is acceptable it throws
 * {@link ClusterOperationRefusedException}; the sealer counts {@code rotation.blocked} and lets it reach the
 * caller, which is the 0.7 refusal code.
 */
@FunctionalInterface
public interface WriteKeyPolicy {

    /** @throws ClusterOperationRefusedException when no held key may be written */
    ClusterKeys.Key choose(ClusterKeys keys);

    /** The default: always the current key. */
    static WriteKeyPolicy current() {
        return ClusterKeys::current;
    }

    /** A policy that accepts no key: every write is refused. */
    static WriteKeyPolicy refusing() {
        return keys -> {
            throw new ClusterOperationRefusedException(new IllegalStateException("no held key is acceptable"));
        };
    }
}
