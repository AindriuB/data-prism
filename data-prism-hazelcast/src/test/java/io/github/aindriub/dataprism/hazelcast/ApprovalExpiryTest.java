package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/** A state change must not give an approval a longer, or no, life: it still expires at its expiresAt. */
class ApprovalExpiryTest {

    private PrivacyCluster[] members;
    private PrivacyCluster cluster;
    private HazelcastApprovalStore approvals;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("expiry"), OptionalInt.empty());
        cluster = members[0];
        approvals = new HazelcastApprovalStore(cluster);
    }

    @AfterEach
    void stop() {
        for (PrivacyCluster member : members) {
            member.close();
        }
    }

    private ApprovalRequest pending(String id, Instant now) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, "alice", "client", "scope-1", "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", now, now.plus(1, ChronoUnit.HOURS), Status.PENDING,
                null, null);
    }

    private void assertExpiresAt(String id, ApprovalRequest request) {
        IMap<String, String> map = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        long expiration = map.getEntryView(TestMembers.SCOPE_KEYS.approval(request.scopeId(), id)).getExpirationTime();
        assertThat(expiration).isBetween(request.expiresAt().toEpochMilli() - 1000,
                request.expiresAt().toEpochMilli() + 1000);
    }

    @Test
    void approveRejectAndConsumeKeepTheExpiry() {
        Instant now = Instant.now();
        ApprovalRequest toApprove = pending("approve-1", now);
        ApprovalRequest toReject = pending("reject-1", now);
        ApprovalRequest toConsume = pending("consume-1", now);
        for (ApprovalRequest r : new ApprovalRequest[]{toApprove, toReject, toConsume}) {
            approvals.create(r);
            assertExpiresAt(r.approvalId(), r);
        }
        approvals.approve("approve-1", "bob", now);
        assertExpiresAt("approve-1", toApprove);
        approvals.reject("reject-1", "bob", now);
        assertExpiresAt("reject-1", toReject);
        approvals.approve("consume-1", "bob", now);
        approvals.consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-consume-1", now);
        assertExpiresAt("consume-1", toConsume);
        assertThat(approvals.find("consume-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.CONSUMED);
    }
}
