package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Two clusters of one name that each recorded a different fate for one approval, then find each other. */
class ApprovalSplitBrainHealTest {

    private PrivacyCluster first;
    private PrivacyCluster second;

    @AfterEach
    void shutDown() {
        for (PrivacyCluster c : new PrivacyCluster[]{first, second}) {
            if (c != null) {
                c.close();
            }
        }
    }

    private static Config shortMergeDelays(Config config) {
        config.setProperty("hazelcast.merge.first.run.delay.seconds", "5");
        config.setProperty("hazelcast.merge.next.run.delay.seconds", "5");
        return config;
    }

    @Test
    void aConsumedApprovalStaysConsumedAfterTheClustersMerge() throws Exception {
        String name = TestMembers.uniqueName("heal");
        int port = FreePorts.consecutive(2);
        String a = "127.0.0.1:" + port;
        String b = "127.0.0.1:" + (port + 1);
        // The first member lists the second, but starts alone, so it forms a cluster of one. The second lists
        // only itself. Each is a cluster of one with the same name, and the first keeps looking for the second.
        first = PrivacyCluster.embedded(shortMergeDelays(ClusterMembership.tcpIp(name, List.of(a, b))
                .withPort(port).withInterface("127.0.0.1").toConfig()), false, OptionalInt.empty(),
                TestMembers.KEYS);
        second = PrivacyCluster.embedded(shortMergeDelays(ClusterMembership.tcpIp(name, List.of(b))
                .withPort(port + 1).withInterface("127.0.0.1").toConfig()), false, OptionalInt.empty(),
                TestMembers.KEYS);
        assertThat(first.instance().getCluster().getMembers()).hasSize(1);
        assertThat(second.instance().getCluster().getMembers()).hasSize(1);

        var onFirst = new HazelcastApprovalStore(first);
        var onSecond = new HazelcastApprovalStore(second);
        // One creation that both sides hold, as if made before the split: the second side gets copies of the
        // first side's entry, claim and pending slot, token included. (Two separate creations of one id would
        // keep the surviving side's entry whatever the statuses; see ApprovalMergePolicyTest.)
        onFirst.create(TestMembers.request("heal-1", "scope-1", Status.PENDING));
        for (String map : List.of(PrivacyCluster.APPROVAL_MAP, PrivacyCluster.APPROVAL_ID_MAP,
                PrivacyCluster.APPROVAL_PENDING_MAP)) {
            first.instance().<String, String>getMap(map).forEach(
                    (key, value) -> second.instance().<String, String>getMap(map).put(key, value));
        }
        for (var store : List.of(onFirst, onSecond)) {
            store.approve("heal-1", "bob", NOW);
        }
        assertThat(onSecond.consumeApproved(io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind.TOOL_CALL,
                "alice", "scope-1", "search", "fp-heal-1", NOW)).isPresent();

        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && first.instance().getCluster().getMembers().size() < 2) {
            Thread.sleep(500);
        }
        assertThat(first.instance().getCluster().getMembers()).as("the clusters merged within 60 s").hasSize(2);
        // Merging is asynchronous after the membership changes.
        deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline
                && onFirst.find("heal-1").map(r -> r.status() != Status.CONSUMED).orElse(true)) {
            Thread.sleep(250);
        }
        assertThat(onFirst.find("heal-1")).get().extracting(r -> r.status()).isEqualTo(Status.CONSUMED);
        assertThat(onSecond.find("heal-1")).get().extracting(r -> r.status()).isEqualTo(Status.CONSUMED);
    }
}
