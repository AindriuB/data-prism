package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ending a scope is still a prefix match once keys are keyed names: {@code endScope}, {@code forgetScope} and the
 * budget's {@code forget} remove exactly the entries of one scope and leave a second scope's entries, on one member
 * and on two.
 */
class KeyedScopeEndTest {

    private static final String ENDED = "scope-ended";
    private static final String KEPT = "scope-kept";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private PrivacyCluster[] members;

    @AfterEach
    void stop() {
        if (members != null) {
            for (PrivacyCluster member : members) {
                member.close();
            }
        }
    }

    private void singleMember() {
        int port = FreePorts.consecutive(1);
        members = new PrivacyCluster[]{TestMembers.member(TestMembers.uniqueName("scope-end-1"), port,
                java.util.List.of("127.0.0.1:" + port), OptionalInt.empty())};
    }

    private void twoMembers() {
        members = TestMembers.pair(TestMembers.uniqueName("scope-end-2"), OptionalInt.empty());
    }

    private static PrivacyContext scope(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static final SyntheticValueSource GENERATOR =
            (subjectId, namespace, context) -> "generated:" + context.scopeId() + ":" + subjectId;

    /** Writes through the first member; reads and ends through the last, so two members really share the state. */
    private void populate(String scopeId, String subject) {
        PrivacyCluster writer = members[0];
        var caching = new CachingSyntheticValueSource(GENERATOR, writer);
        caching.syntheticValue(subject, PrivacyNamespace.PERSON_NAME, scope(scopeId));
        new HazelcastScopeBudget(writer, TIMEOUT).tryRead(scopeId, subject, 5);
        new HazelcastOversightState(writer, TIMEOUT).pauseScope(scopeId);
        new HazelcastApprovalStore(writer, TIMEOUT).create(
                TestMembers.request("approval-" + scopeId, scopeId, Status.PENDING));
    }

    private int total() {
        PrivacyCluster any = members[members.length - 1];
        return any.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()
                + any.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()
                + any.instance().getMap(PrivacyCluster.BUDGET_MAP).size()
                + any.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()
                + any.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).size()
                + any.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).size()
                + any.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).size();
    }

    private void endScopeLeavesTheOtherScope() {
        populate(ENDED, "subject-1");
        int one = total();
        populate(KEPT, "subject-1");
        int two = total();
        assertThat(one).isPositive();
        assertThat(two).as("the second scope adds entries to every map but the pending set").isGreaterThan(one);

        PrivacyCluster ender = members[members.length - 1];
        new ScopeIdentityIndex(ender, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT)
                .endScope(ENDED);

        assertThat(new HazelcastOversightState(ender, TIMEOUT).scopePaused(ENDED)).isFalse();
        assertThat(new HazelcastOversightState(ender, TIMEOUT).scopePaused(KEPT)).isTrue();
        assertThat(new HazelcastScopeBudget(ender, TIMEOUT).reads(ENDED, "subject-1")).isZero();
        assertThat(new HazelcastScopeBudget(ender, TIMEOUT).reads(KEPT, "subject-1")).isEqualTo(1);
        var approvals = new HazelcastApprovalStore(ender, TIMEOUT);
        assertThat(approvals.find("approval-" + ENDED)).isEmpty();
        assertThat(approvals.find("approval-" + KEPT)).isPresent();
        assertThat(ender.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()).isZero();
        assertThat(ender.instance().getMap(PrivacyCluster.BUDGET_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()).isEqualTo(1);
        // Both scopes' requester is the same, so the pending set is shared; only the kept scope's id is left in it.
        String pendingKey = TestMembers.SCOPE_KEYS.pending(
                io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind.TOOL_CALL, "alice");
        assertThat(PendingSet.decode((String) ender.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP)
                .get(pendingKey))).containsOnlyKeys("approval-" + KEPT);
    }

    @Test
    void endScopeRemovesExactlyOneScopeOnOneMember() {
        singleMember();
        endScopeLeavesTheOtherScope();
    }

    @Test
    void endScopeRemovesExactlyOneScopeOnTwoMembers() {
        twoMembers();
        endScopeLeavesTheOtherScope();
    }

    private void forgetScopeLeavesTheOtherScope() {
        populate(ENDED, "subject-1");
        populate(KEPT, "subject-1");
        PrivacyCluster ender = members[members.length - 1];
        var approvals = new HazelcastApprovalStore(ender, TIMEOUT);
        approvals.forgetScope(ENDED);
        assertThat(approvals.find("approval-" + ENDED)).isEmpty();
        assertThat(approvals.find("approval-" + KEPT)).isPresent();
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).size()).isEqualTo(1);
        // The id of the forgotten scope is free again; the other scope's id is not.
        approvals.create(TestMembers.request("approval-" + ENDED, ENDED, Status.PENDING));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvals.create(TestMembers.request("approval-" + KEPT, KEPT, Status.PENDING)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forgetScopeRemovesExactlyOneScopesApprovalsOnOneMember() {
        singleMember();
        forgetScopeLeavesTheOtherScope();
    }

    @Test
    void forgetScopeRemovesExactlyOneScopesApprovalsOnTwoMembers() {
        twoMembers();
        forgetScopeLeavesTheOtherScope();
    }

    private void budgetForgetLeavesTheOtherScope() {
        PrivacyCluster writer = members[0];
        var budget = new HazelcastScopeBudget(writer, TIMEOUT);
        budget.tryRead(ENDED, "subject-1", 5);
        budget.tryRead(ENDED, "subject-2", 5);
        budget.tryRead(KEPT, "subject-1", 5);
        var other = new HazelcastScopeBudget(members[members.length - 1], TIMEOUT);
        other.forget(ENDED);
        assertThat(other.reads(ENDED, "subject-1")).isZero();
        assertThat(other.reads(ENDED, "subject-2")).isZero();
        assertThat(other.reads(KEPT, "subject-1")).isEqualTo(1);
        assertThat(writer.instance().getMap(PrivacyCluster.BUDGET_MAP).size()).isEqualTo(1);
    }

    @Test
    void budgetForgetRemovesExactlyOneScopeOnOneMember() {
        singleMember();
        budgetForgetLeavesTheOtherScope();
    }

    @Test
    void budgetForgetRemovesExactlyOneScopeOnTwoMembers() {
        twoMembers();
        budgetForgetLeavesTheOtherScope();
    }

    @Test
    void theGlobalAndToolPausesSurviveEndingAScope() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseAll();
        state.pauseTool("search");
        state.pauseScope(ENDED);
        new ScopeIdentityIndex(members[0], io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT)
                .endScope(ENDED);
        assertThat(state.allPaused()).isTrue();
        assertThat(state.toolPaused("search")).isTrue();
        assertThat(state.scopePaused(ENDED)).isFalse();
        assertThat(state.snapshot().pausedScopes()).isEmpty();
    }
}
