package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One set of rules, run against every {@link ApprovalStore} implementation. */
class ApprovalStoreContractTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String MEMORY = "memory";
    private static final String HAZELCAST = "hazelcast";

    private PrivacyCluster cluster;

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private ApprovalStore store(String kind) {
        if (kind.equals(MEMORY)) {
            return new InMemoryApprovalStore();
        }
        Config config = new Config();
        config.setClusterName("dataprism-approval-contract-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, false);
        return new HazelcastApprovalStore(cluster);
    }

    private static ApprovalRequest request(String id, String scope, Instant expiresAt, Status status) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, "alice", "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, expiresAt, status, null, null);
    }

    private static ApprovalRequest request(String id, String scope) {
        return request(id, scope, NOW.plus(1, ChronoUnit.HOURS), Status.PENDING);
    }

    private static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (ApprovalRefusedException refused) {
            return refused.code();
        }
        throw new AssertionError("expected a refusal");
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void createFindAndPendingListing(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest created = request("a1", "CASE-A");

        assertThat(store.create(created)).isEqualTo(created);

        assertThat(store.find("a1")).contains(created);
        assertThat(store.find("missing")).isEmpty();
        assertThat(store.pending(NOW)).containsExactly(created);
        assertThat(store.pending(NOW.plus(2, ChronoUnit.HOURS))).isEmpty();
        assertThat(store.findPending(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-a1", NOW))
                .contains(created);
        assertThat(store.findPending(Kind.TOOL_CALL, "bob", "CASE-A", "search", "fp-a1", NOW)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aNonPendingRequestIsRefusedAndNothingIsStored(String kind) {
        ApprovalStore store = store(kind);
        for (Status status : new Status[]{Status.APPROVED, Status.REJECTED, Status.CONSUMED, Status.EXPIRED}) {
            ApprovalRequest decided = request("n-" + status, "CASE-A", NOW.plus(1, ChronoUnit.HOURS), status);
            assertThatThrownBy(() -> store.create(decided)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.find(decided.approvalId())).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aDuplicateIdIsRefusedAndTheFirstIsKept(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest first = request("dup", "CASE-A");
        store.create(first);

        assertThatThrownBy(() -> store.create(request("dup", "CASE-A")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create(request("dup", "CASE-B")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.find("dup")).contains(first);
        assertThat(store.pending(NOW)).containsExactly(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aDuplicateOfADecidedRequestIsRefused(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("done", "CASE-A"));
        ApprovalRequest approved = store.approve("done", "bob", NOW);

        assertThatThrownBy(() -> store.create(request("done", "CASE-A")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.find("done")).contains(approved);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aNullApproverIsRefusedAndStateIsUnchanged(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest pending = request("a1", "CASE-A");
        store.create(pending);

        assertThatThrownBy(() -> store.approve("a1", null, NOW)).isInstanceOf(NullPointerException.class);

        assertThat(store.find("a1")).contains(pending);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void refusalCodes(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("self", "CASE-A"));
        store.create(request("once", "CASE-A"));
        store.create(request("short", "CASE-A", NOW.plus(1, ChronoUnit.MINUTES), Status.PENDING));

        assertThat(codeOf(() -> store.approve("self", "alice", NOW))).isEqualTo(Code.SELF_APPROVAL);
        assertThat(codeOf(() -> store.approve("nope", "bob", NOW))).isEqualTo(Code.UNKNOWN_APPROVAL);
        store.approve("once", "bob", NOW);
        assertThat(codeOf(() -> store.approve("once", "bob", NOW))).isEqualTo(Code.NOT_PENDING);
        assertThat(codeOf(() -> store.approve("short", "bob", NOW.plus(2, ChronoUnit.MINUTES))))
                .isEqualTo(Code.EXPIRED);
        assertThat(codeOf(() -> store.approve("short", "bob", NOW))).isEqualTo(Code.NOT_PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void rejectingAnUnknownIdIsRefused(String kind) {
        ApprovalStore store = store(kind);

        assertThat(codeOf(() -> store.reject("nope", "bob", NOW))).isEqualTo(Code.UNKNOWN_APPROVAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void anApprovalIsConsumedOnce(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("a1", "CASE-A"));
        store.approve("a1", "bob", NOW);

        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-a1", NOW))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(Status.CONSUMED));
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-a1", NOW)).isEmpty();
        assertThat(store.find("a1")).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(Status.CONSUMED));
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void forgettingAScopeRemovesOnlyThatScope(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("a1", "CASE-A"));
        ApprovalRequest other = request("b1", "CASE-B");
        store.create(other);

        store.forgetScope("CASE-A");

        assertThat(store.find("a1")).isEmpty();
        assertThat(store.find("b1")).contains(other);
        assertThat(store.pending(NOW)).containsExactly(other);
    }
}
