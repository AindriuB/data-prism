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

    /** A member that is already unverified when an instance starts gets no grace from it. */
    @Test
    void anInstanceThatStartsWhileAnUnverifiedMemberHasBeenPresentLongerThanTheGraceDoesNotServe() {
        ClusterSecuritySettings settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        a = fixture.keyed(name, first, all, TestMembers.KEYS, settings, metricsA, logA, true);
        outsider = Fixture.plain(name, first + 2, all);
        assertThat(Fixture.awaitMembers(a, 2)).isTrue();
        for (int step = 0; step < 2; step++) {
            fixture.clock.advance(GRACE);
            a.memberCards().orElseThrow().tick();
        }
        assertThat(a.securityStatus().serving()).isFalse();

        PrivacyCluster late = fixture.keyed(name, first + 1, all, TestMembers.KEYS, settings, new Fixture.Metrics(),
                new RecordingLogger(), true);
        assertThat(Fixture.awaitMembers(late, 3)).isTrue();
        late.memberCards().orElseThrow().tick();
        assertThat(late.securityStatus().serving()).as("no grace for a member that was there first").isFalse();
        assertThat(late.securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER);

        outsider.shutdown();
        assertThat(Fixture.awaitMembers(late, 2)).isTrue();
        late.memberCards().orElseThrow().tick();
        assertThat(late.securityStatus().serving()).as("it serves once nobody is unverified").isTrue();
    }

    /**
     * The no-grace rule holds until an instance has once judged the cluster serving (OD-W6-7), not merely until its
     * first look: here the first judgement is not serving for another reason, with no unverified member.
     */
    @Test
    void anOutsiderThatJoinsBeforeTheInstanceHasEverServedGetsNoGrace() {
        String clusterName = TestMembers.uniqueName("neverserved");
        int port = FreePorts.consecutive(3);
        List<String> everyone = peers(port, 2);
        var settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        // A keyed peer whose key check value for k0 differs: the instance's first judgement is not serving.
        PrivacyCluster peer = fixture.keyed(clusterName, port + 1, everyone,
                Fixture.keys("test-cluster-1", 'c', "k0", 'y'), settings, new Fixture.Metrics(),
                new RecordingLogger(), true);
        PrivacyCluster c = fixture.keyed(clusterName, port, everyone, Fixture.keys("test-cluster-1", 'c', "k0", 'x'),
                settings, metricsA, logA, true);
        assertThat(Fixture.awaitMembers(c, 2)).isTrue();
        MemberCards cards = c.memberCards().orElseThrow();
        cards.tick();
        assertThat(c.securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.KEY_CHECK_MISMATCH);
        assertThat(c.securityStatus().unverifiedCount()).isZero();

        HazelcastInstance stranger = Fixture.plain(clusterName, port + 2, everyone);
        assertThat(Fixture.awaitMembers(c, 3)).isTrue();
        cards.tick();
        peer.close();
        assertThat(Fixture.awaitMembers(c, 2)).isTrue();
        cards.tick();

        assertThat(c.securityStatus().reason()).as("the stranger was there before this instance ever served")
                .isEqualTo(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER);
        stranger.shutdown();
        assertThat(Fixture.awaitMembers(c, 1)).isTrue();
        cards.tick();
        assertThat(c.securityStatus().serving()).isTrue();
    }

    // ---- the grace is the cluster's, not a member's (OD-W6-8) ------------------------------------------------

    private List<String> peers(int port, int outsiders) {
        List<String> list = new ArrayList<>();
        list.add("127.0.0.1:" + port);
        for (int i = 1; i <= outsiders; i++) {
            list.add("127.0.0.1:" + (port + i));
        }
        return list;
    }

    /** An outsider that leaves and a fresh one that joins before the next look do not restart the clock. */
    @Test
    void aFreshOutsiderEveryFewSecondsDoesNotResetTheGrace() {
        int port = FreePorts.consecutive(5);
        List<String> everyone = peers(port, 4);
        PrivacyCluster serving = fixture.keyed(name, port, everyone, TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), metricsA, logA, true);
        HazelcastInstance current = Fixture.plain(name, port + 1, everyone);
        assertThat(Fixture.awaitMembers(serving, 2)).isTrue();
        serving.memberCards().orElseThrow().tick();

        for (int round = 2; round <= 4; round++) {
            fixture.clock.advance(Duration.ofSeconds(14));
            serving.memberCards().orElseThrow().tick();
            boolean overThirty = round == 4;
            assertThat(serving.securityStatus().serving()).as("round %d", round).isEqualTo(!overThirty);
            HazelcastInstance next = Fixture.plain(name, port + round, everyone);
            assertThat(Fixture.awaitMembers(serving, 3)).isTrue();
            current.shutdown();
            assertThat(Fixture.awaitMembers(serving, 2)).isTrue();
            serving.memberCards().orElseThrow().tick();
            current = next;
        }
        assertThat(serving.securityStatus().serving()).as("42 s of unverified members, never none").isFalse();
    }

    /** A serving instance on its own: outsiders are started by the tests, one at a time. */
    private PrivacyCluster solo(int port, List<String> everyone) {
        return fixture.keyed(name, port, everyone, TestMembers.KEYS, Fixture.settings(UnverifiedMemberAction.REFUSE),
                metricsA, logA, true);
    }

    private HazelcastInstance arrives(PrivacyCluster serving, int port, List<String> everyone, int members) {
        HazelcastInstance outsider = Fixture.plain(name, port, everyone);
        assertThat(Fixture.awaitMembers(serving, members)).isTrue();
        serving.memberCards().orElseThrow().tick();
        return outsider;
    }

    private void leaves(PrivacyCluster serving, HazelcastInstance outsider, int membersLeft) {
        outsider.shutdown();
        assertThat(Fixture.awaitMembers(serving, membersLeft)).isTrue();
        serving.memberCards().orElseThrow().tick();
    }

    /** OD-W6-9 (a): a brief absence leaves the running clock alone, so a rejoin with a fresh UUID is no reset. */
    @Test
    void anOutsiderThatLeavesAndRejoinsWithAFreshUuidDoesNotRestartTheGrace() {
        int port = FreePorts.consecutive(5);
        List<String> everyone = peers(port, 4);
        PrivacyCluster serving = solo(port, everyone);
        MemberCards cards = serving.memberCards().orElseThrow();

        HazelcastInstance outsider = arrives(serving, port + 1, everyone, 2);
        for (int round = 2; round <= 4; round++) {
            fixture.clock.advance(Duration.ofSeconds(14));
            cards.tick();
            assertThat(serving.securityStatus().serving()).as("%d s in", (round - 1) * 14).isEqualTo(round < 4);
            leaves(serving, outsider, 1);
            assertThat(serving.securityStatus().unverifiedCount()).as("a look with none").isZero();
            outsider = arrives(serving, port + round, everyone, 2);
        }
        assertThat(serving.securityStatus().serving()).as("42 s since the first, with gaps of no time").isFalse();
        outsider.shutdown();
    }

    /** One brief absence, then a rejoin: the 29 s already counted stay counted. */
    @Test
    void aBriefAbsenceDoesNotResetTheGrace() {
        int port = FreePorts.consecutive(3);
        List<String> everyone = peers(port, 2);
        PrivacyCluster serving = solo(port, everyone);

        HazelcastInstance first = arrives(serving, port + 1, everyone, 2);
        fixture.clock.advance(Duration.ofSeconds(29));
        leaves(serving, first, 1);
        HazelcastInstance second = arrives(serving, port + 2, everyone, 2);
        assertThat(serving.securityStatus().serving()).isTrue();
        fixture.clock.advance(Duration.ofSeconds(2));
        serving.memberCards().orElseThrow().tick();
        assertThat(serving.securityStatus().serving()).as("31 s since the first arrived").isFalse();
        second.shutdown();
    }

    /** The clear period is not credited if an unverified member comes back inside it. */
    @Test
    void aReturnInsideTheClearPeriodCancelsTheClearPeriod() {
        int port = FreePorts.consecutive(4);
        List<String> everyone = peers(port, 3);
        PrivacyCluster serving = solo(port, everyone);
        MemberCards cards = serving.memberCards().orElseThrow();

        HazelcastInstance first = arrives(serving, port + 1, everyone, 2);
        fixture.clock.advance(Duration.ofSeconds(10));
        leaves(serving, first, 1);
        HazelcastInstance second = arrives(serving, port + 2, everyone, 2);
        fixture.clock.advance(Duration.ofSeconds(30));
        cards.tick();
        assertThat(serving.securityStatus().serving()).isFalse();
        leaves(serving, second, 1);
        HazelcastInstance third = arrives(serving, port + 3, everyone, 2);

        assertThat(serving.securityStatus().serving())
                .as("the 30 s since the first look with none was interrupted by the second outsider").isFalse();
        third.shutdown();
    }

    /** After a genuine absence of at least the grace, a new outsider has a fresh grace. */
    @Test
    void aNewOutsiderAfterAGenuineAbsenceOfTheGraceGetsAFreshGrace() {
        int port = FreePorts.consecutive(3);
        List<String> everyone = peers(port, 2);
        PrivacyCluster serving = solo(port, everyone);
        MemberCards cards = serving.memberCards().orElseThrow();

        HazelcastInstance first = arrives(serving, port + 1, everyone, 2);
        fixture.clock.advance(Duration.ofSeconds(31));
        cards.tick();
        assertThat(serving.securityStatus().serving()).isFalse();
        leaves(serving, first, 1);
        assertThat(serving.securityStatus().serving()).as("it serves with none present").isTrue();

        fixture.clock.advance(Duration.ofSeconds(30));
        cards.tick();
        HazelcastInstance second = arrives(serving, port + 2, everyone, 2);
        assertThat(serving.securityStatus().serving()).as("a fresh grace").isTrue();
        fixture.clock.advance(Duration.ofSeconds(29));
        cards.tick();
        assertThat(serving.securityStatus().serving()).isTrue();
        fixture.clock.advance(Duration.ofSeconds(1));
        cards.tick();
        assertThat(serving.securityStatus().serving()).isFalse();
        second.shutdown();
    }

    /** A look that fails changes neither clock. */
    @Test
    void aFailedLookLeavesTheGraceClockWhereItWas() {
        int port = FreePorts.consecutive(2);
        List<String> everyone = peers(port, 1);
        PrivacyCluster serving = solo(port, everyone);
        MemberCards cards = serving.memberCards().orElseThrow();

        HazelcastInstance outsider = arrives(serving, port + 1, everyone, 2);
        fixture.clock.advance(Duration.ofSeconds(29));
        cards.lookFault = () -> {
            throw new IllegalStateException("the cluster could not be read");
        };
        cards.tick();
        assertThat(serving.securityStatus().serving()).isFalse();
        cards.lookFault = () -> { };
        fixture.clock.advance(Duration.ofSeconds(2));
        cards.tick();

        assertThat(serving.securityStatus().reason()).as("31 s since the outsider arrived")
                .isEqualTo(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER);
        outsider.shutdown();
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
