package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCardTest.Fixture;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.github.aindriub.dataprism.hazelcast.MemberCardTest.GRACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10: a member that holds no key, or cannot prove it, is counted, and under {@code refuse} every instance stops
 * serving once the grace period is over (docs/plan/design/cluster-security.md 2.4 d2, 4.4). Time is a clock the test
 * moves; the stores the gate reaches are the ones that never call the sealer as well as the ones that do.
 */
class UnverifiedMemberTest {

    private static final String CODE = ClusterOperationRefusedException.CODE;

    private final Fixture fixture = new Fixture();
    private final String name = TestMembers.uniqueName("unverified");
    private final int first = FreePorts.consecutive(3);
    private final List<String> all = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1),
            "127.0.0.1:" + (first + 2));

    private PrivacyCluster a;
    private PrivacyCluster b;
    private HazelcastInstance outsider;
    private final Fixture.Metrics metricsA = new Fixture.Metrics();
    private final Fixture.Metrics metricsB = new Fixture.Metrics();
    private final RecordingLogger logA = new RecordingLogger();

    @AfterEach
    void shutDown() {
        fixture.closeAll();
    }

    /** Two keyed members, then a third that holds no key and writes no card. */
    private void clusterWithAnOutsider(UnverifiedMemberAction action) {
        ClusterSecuritySettings settings = Fixture.settings(action);
        a = fixture.keyed(name, first, all, TestMembers.KEYS, settings, metricsA, logA, true);
        b = fixture.keyed(name, first + 1, all, TestMembers.KEYS, settings, metricsB, new RecordingLogger(), true);
        outsider = Fixture.plain(name, first + 2, all);
        assertThat(Fixture.awaitMembers(a, 3)).isTrue();
        assertThat(Fixture.awaitMembers(b, 3)).isTrue();
    }

    private void look() {
        a.memberCards().orElseThrow().tick();
        b.memberCards().orElseThrow().tick();
    }

    private void outsiderLeaves() {
        outsider.shutdown();
        assertThat(Fixture.awaitMembers(a, 2)).isTrue();
        assertThat(Fixture.awaitMembers(b, 2)).isTrue();
    }

    // ---- T10 -------------------------------------------------------------------------------------------------

    @Test
    void aMemberThatHoldsNoKeyIsUnverifiedAndUnderRefuseEveryInstanceStopsServingAfterTheGrace() {
        clusterWithAnOutsider(UnverifiedMemberAction.REFUSE);
        look();

        assertThat(a.securityStatus().unverifiedCount()).isEqualTo(1);
        assertThat(b.securityStatus().unverifiedCount()).isEqualTo(1);
        assertThat(metricsA.gauge(Metric.CLUSTER_MEMBERS_UNVERIFIED)).isEqualTo(1.0);
        assertThat(a.securityStatus().serving()).as("inside the grace").isTrue();
        assertThat(a.servingInstance()).isNotNull();

        fixture.clock.advance(GRACE);
        look();

        for (PrivacyCluster member : List.of(a, b)) {
            assertThat(member.securityStatus().serving()).isFalse();
            assertThat(member.securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER);
            assertThatThrownBy(() -> new HazelcastScopeBudget(member).tryRead("CASE-1", "subject-1", 3))
                    .isInstanceOf(ClusterOperationRefusedException.class)
                    .satisfies(refusal -> assertThat(((PrivacyRefusedException) refusal).code()).isEqualTo(CODE));
        }

        outsiderLeaves();
        look();
        for (PrivacyCluster member : List.of(a, b)) {
            assertThat(member.securityStatus().serving()).as("service resumes without a restart").isTrue();
            assertThat(new HazelcastScopeBudget(member).tryRead("CASE-1", "subject-1", 3)).isTrue();
        }
        assertThat(metricsA.gauge(Metric.CLUSTER_MEMBERS_UNVERIFIED)).isZero();
    }

    /** A member that runs without cards stands in for a 0.7 member, or any member without the key. */
    @Test
    void aMemberRunningWithoutCardsLikeA07MemberIsCountedUnverifiedExactlyLikeAKeylessMember() {
        clusterWithAnOutsider(UnverifiedMemberAction.REFUSE);
        look();
        UUID outsiderId = outsider.getCluster().getLocalMember().getUuid();

        assertThat(a.memberCards().orElseThrow().verifiedMembers()).doesNotContain(outsiderId).hasSize(2);
        assertThat(a.securityStatus().unverifiedCount()).isEqualTo(1);
        // Its map entries, if any, are not cards: the member list decides, and it has no card key.
        assertThat(Fixture.cardMap(a).keySet()).hasSize(3).contains(ClusterSentinel.KEY);
        fixture.clock.advance(GRACE);
        look();
        assertThat(a.securityStatus().serving()).isFalse();
    }

    @Test
    void underAlertTheMemberKeepsServingAndTheGaugeStillReadsOne() {
        clusterWithAnOutsider(UnverifiedMemberAction.ALERT);
        fixture.clock.advance(GRACE.plusSeconds(1));
        look();

        assertThat(a.securityStatus().serving()).isTrue();
        assertThat(a.securityStatus().unverifiedCount()).isEqualTo(1);
        assertThat(metricsA.gauge(Metric.CLUSTER_MEMBERS_UNVERIFIED)).isEqualTo(1.0);
        assertThat(a.servingInstance()).isNotNull();
        assertThat(new HazelcastScopeBudget(a).tryRead("CASE-1", "subject-1", 3)).isTrue();
    }

    // ---- the gate on the stores that never call the sealer ---------------------------------------------------

    private static final class Generator implements SyntheticValueSource {
        @Override
        public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
            return "generated:" + subjectId;
        }
    }

    private static PrivacyContext scope() {
        return new PrivacyContext("CASE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static void refused(ThrowingCallable call, String store) {
        assertThatThrownBy(call).as(store).isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(refusal -> assertThat(((PrivacyRefusedException) refusal).code()).isEqualTo(CODE));
    }

    @Test
    void withTheGateClosedEachStoreRefusesAndAfterItReopensEachServesAgain() {
        clusterWithAnOutsider(UnverifiedMemberAction.REFUSE);
        look();
        Instant now = TestMembers.NOW;
        var approvals = new HazelcastApprovalStore(a);
        var index = new ScopeIdentityIndex(a);
        var budget = new HazelcastScopeBudget(a);
        var rates = new HazelcastCallerRateLimiter(a);
        var cache = new CachingSyntheticValueSource(new Generator(), a);

        fixture.clock.advance(GRACE);
        look();
        assertThat(a.securityStatus().serving()).isFalse();

        refused(() -> approvals.create(TestMembers.request("ap-1", "CASE-1", Status.PENDING)), "approval create");
        refused(() -> approvals.find("ap-1"), "approval find");
        refused(() -> approvals.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-1", "search", "fp-ap-1", now),
                "approval consumeApproved");
        refused(() -> index.subjectFor("CASE-1", PrivacyNamespace.PERSON_NAME, "synthetic"), "re-identification index");
        refused(() -> budget.tryRead("CASE-1", "subject-1", 3), "budget");
        refused(() -> rates.tryAcquire("alice", 5, Duration.ofMinutes(1), now), "caller rate");

        // Identity recomputes (design 2.3.1): the generator's value, with the identity map neither read nor written.
        assertThat(cache.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, scope()))
                .isEqualTo("generated:subject-1");
        assertThat(a.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()).isZero();
        assertThat(a.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()).isZero();

        // The public instance() is exactly what it was.
        assertThat(a.instance()).isNotNull();
        assertThat(a.instance().getCluster().getMembers()).hasSize(3);

        outsiderLeaves();
        look();
        assertThat(a.securityStatus().serving()).isTrue();

        assertThat(approvals.create(TestMembers.request("ap-1", "CASE-1", Status.PENDING)).approvalId())
                .isEqualTo("ap-1");
        assertThat(approvals.find("ap-1")).isPresent();
        assertThat(budget.tryRead("CASE-1", "subject-1", 3)).isTrue();
        assertThat(rates.tryAcquire("alice", 5, Duration.ofMinutes(1), now)).isTrue();
        assertThat(cache.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, scope()))
                .isEqualTo("generated:subject-1");
        assertThat(a.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()).isEqualTo(1);
        assertThat(index.subjectFor("CASE-1", PrivacyNamespace.PERSON_NAME, "generated:subject-1"))
                .contains("subject-1");
    }

    // ---- what a closed gate never blocks ---------------------------------------------------------------------

    @Test
    void whileTheGateIsClosedTheMemberCardIsStillRefreshedAndPauseStateIsStillReadAndWritten() {
        clusterWithAnOutsider(UnverifiedMemberAction.REFUSE);
        look();
        fixture.clock.advance(GRACE);
        String key = a.memberCards().orElseThrow().keyForTest(Fixture.uuidOf(a));
        String before = Fixture.cardMap(a).get(key);
        look();
        assertThat(a.securityStatus().serving()).isFalse();
        assertThat(Fixture.cardMap(a).get(key)).as("the card was refreshed while closed").isNotEqualTo(before);
        assertThat(a.memberCards().orElseThrow().verifiedMembers()).contains(Fixture.uuidOf(a));

        // The pause-document map is read through the closed gate, and a pause (the safe direction) is written.
        var state = new HazelcastOversightState(a);
        assertThat(state.allPaused()).isFalse();
        assertThat(a.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).isEmpty()).isTrue();
        state.pauseAll();
        assertThat(state.allPaused()).isTrue();
    }

    // ---- what is said about it -------------------------------------------------------------------------------

    @Test
    void noLogLineAndNoStatusTextNamesAKeyAKeyIdAnAddressOrAMemberUuid() {
        clusterWithAnOutsider(UnverifiedMemberAction.REFUSE);
        look();
        UUID outsiderId = outsider.getCluster().getLocalMember().getUuid();
        fixture.clock.advance(GRACE);
        look();
        outsiderLeaves();
        look();

        List<String> forbidden = new ArrayList<>(List.of("127.0.0.1", "test-cluster-1", "test-name-1"));
        forbidden.add(Fixture.uuidOf(a).toString());
        forbidden.add(Fixture.uuidOf(b).toString());
        forbidden.add(outsiderId.toString());
        byte[] clusterMaterial = Fixture.filled('c');
        byte[] nameMaterial = Fixture.filled('n');
        for (Base64.Encoder encoder : List.of(Base64.getEncoder(), Base64.getUrlEncoder())) {
            forbidden.add(encoder.encodeToString(clusterMaterial));
            forbidden.add(encoder.encodeToString(nameMaterial));
        }
        forbidden.add(new String(clusterMaterial));

        assertThat(logA.lines()).as("the state changes were logged").hasSizeGreaterThanOrEqualTo(2);
        for (RecordingLogger.Line line : logA.lines()) {
            assertThat(line.message()).doesNotContain(forbidden.toArray(new String[0]));
            assertThat(line.withThrowable()).isFalse();
        }
        assertThat(a.securityStatus().text()).doesNotContain(forbidden.toArray(new String[0]));
    }
}
