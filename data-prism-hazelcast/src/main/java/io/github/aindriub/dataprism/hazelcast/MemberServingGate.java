package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@link ServingGate} of a member: closed whenever the cluster-security status says the member is not serving.
 *
 * <p>The gate guards {@code Sealer.seal} and {@code Sealer.open} of the member's sealed stores, and
 * {@code PrivacyCluster.servingInstance()}. The member-card map and the pause-document map never go through it: the
 * cards use a sealer of their own, so a member can still prove itself and read or write pause state while closed.
 * A status that cannot be read closes the gate, because an unknown state is not a serving one.
 */
final class MemberServingGate implements ServingGate {

    private final Supplier<ClusterSecurityStatus> status;

    MemberServingGate(Supplier<ClusterSecurityStatus> status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public boolean serving() {
        ClusterSecurityStatus current = status.get();
        return current != null && current.serving();
    }
}
