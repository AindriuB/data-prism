package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalInt;

/** Starts loopback members for the cluster tests, on ports no other build is given. */
final class TestMembers {

    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private TestMembers() {
    }

    /** A member that lists {@code peers} to join, with no split-brain protection unless {@code quorum} says so. */
    static PrivacyCluster member(String clusterName, int port, List<String> peers, OptionalInt quorum) {
        Config config = ClusterMembership.tcpIp(clusterName, peers).withPort(port)
                .withInterface("127.0.0.1").toConfig();
        return PrivacyCluster.embedded(config, false, quorum);
    }

    /** Two members that join each other. */
    static PrivacyCluster[] pair(String clusterName, OptionalInt quorum) {
        int first = FreePorts.consecutive(2);
        List<String> both = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1));
        PrivacyCluster a = member(clusterName, first, both, quorum);
        PrivacyCluster b = member(clusterName, first + 1, both, quorum);
        return new PrivacyCluster[]{a, b};
    }

    static String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    static ApprovalRequest request(String id, String scope, Status status) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, "alice", "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, NOW.plus(1, ChronoUnit.HOURS), status,
                status == Status.PENDING ? null : "bob", status == Status.PENDING ? null : NOW);
    }
}
