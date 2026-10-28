package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.impl.DefaultNodeContext;
import com.hazelcast.instance.impl.DefaultNodeExtension;
import com.hazelcast.instance.impl.HazelcastInstanceFactory;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.instance.impl.NodeExtension;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCards.Card;
import io.github.aindriub.dataprism.hazelcast.boundary.Inputs;
import io.github.aindriub.dataprism.hazelcast.boundary.MemberCardMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.KeyCheckValue;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The member card: what it holds, how it is read, and what a replayed, deleted, foreign or disagreeing card does
 * (docs/plan/design/cluster-security.md 2.4 d2 and 4.7). Time is a clock the test moves; no test sleeps.
 */
class MemberCardTest {

    static final Duration LEASE = Duration.ofSeconds(60);
    static final Duration GRACE = Duration.ofSeconds(30);

    private final Fixture fixture = new Fixture();

    @AfterEach
    void shutDown() {
        fixture.closeAll();
    }

    /** What the member-card tests and their neighbours share: a movable clock, recorded signals, members. */
    static final class Fixture {

        /** A clock that only moves when a test says so. */
        static final class TestClock extends Clock {
            private volatile Instant now = Instant.parse("2030-01-01T00:00:00Z");

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }

            void advance(Duration by) {
                now = now.plus(by);
            }
        }

        /** Counts and gauges, safe to read from a test thread while a card thread writes. */
        static final class Metrics implements PrivacyMetrics {
            private final Map<Metric, AtomicInteger> counts = new ConcurrentHashMap<>();
            private final Map<Metric, DoubleSupplier> gauges = new ConcurrentHashMap<>();

            @Override
            public void increment(Metric metric) {
                counts.computeIfAbsent(metric, m -> new AtomicInteger()).incrementAndGet();
            }

            @Override
            public void increment(Metric metric, String sourceName) {
                increment(metric);
            }

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {
                increment(metric);
            }

            @Override
            public void gauge(Metric metric, DoubleSupplier supplier) {
                gauges.put(metric, supplier);
            }

            int count(Metric metric) {
                AtomicInteger count = counts.get(metric);
                return count == null ? 0 : count.get();
            }

            double gauge(Metric metric) {
                DoubleSupplier supplier = gauges.get(metric);
                return supplier == null ? Double.NaN : supplier.getAsDouble();
            }
        }

        final TestClock clock = new TestClock();
        private final List<PrivacyCluster> clusters = new ArrayList<>();

        static ClusterSecuritySettings settings(UnverifiedMemberAction action) {
            return new ClusterSecuritySettings(LEASE, ClusterSecuritySettings.DEFAULT_CLOCK_SKEW_TOLERANCE, action,
                    GRACE, true);
        }

        /** Looks at the cluster only when a test says so: the schedule is an hour. */
        PrivacyCluster.Environment environment(Metrics metrics, RecordingLogger log) {
            return new PrivacyCluster.Environment(clock, metrics, log.logger(), Duration.ofHours(1),
                    Duration.ofSeconds(1));
        }

        PrivacyCluster keyed(String clusterName, int port, List<String> peers, ClusterKeys keys,
                             ClusterSecuritySettings settings, Metrics metrics, RecordingLogger log,
                             boolean reidentification) {
            ClusterMembership membership = ClusterMembership.tcpIp(clusterName, peers).withPort(port)
                    .withInterface("127.0.0.1");
            PrivacyCluster started = PrivacyCluster.embedded(membership, reidentification, OptionalInt.of(1),
                    Duration.ofMinutes(1), keys, settings, environment(metrics, log));
            clusters.add(started);
            return started;
        }

        /** A member that holds no key and writes no card: a 0.7 member, or an outsider. */
        static HazelcastInstance plain(String clusterName, int port, List<String> peers) {
            Config config = new Config().setClusterName(clusterName);
            config.getJetConfig().setEnabled(false);
            config.setProperty("hazelcast.phone.home.enabled", "false");
            config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
            var join = config.getNetworkConfig().getJoin();
            join.getAutoDetectionConfig().setEnabled(false);
            join.getMulticastConfig().setEnabled(false);
            join.getTcpIpConfig().setEnabled(true).setMembers(peers);
            config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
            config.setProperty("hazelcast.socket.bind.any", "false");
            return Hazelcast.newHazelcastInstance(config);
        }

        static ClusterKeys keys(String clusterKeyId, char clusterMaterial, String previousId,
                                char previousMaterial) {
            return new ClusterKeys(clusterKeyId, filled(clusterMaterial), null, null,
                    previousId, previousId == null ? null : filled(previousMaterial), "test-name-1", filled('n'));
        }

        static ClusterKeys keysWithNext(String currentId, char currentMaterial, String nextId, char nextMaterial) {
            return new ClusterKeys(currentId, filled(currentMaterial), nextId, filled(nextMaterial), null, null,
                    "test-name-1", filled('n'));
        }

        static byte[] filled(char c) {
            byte[] out = new byte[32];
            Arrays.fill(out, (byte) c);
            return out;
        }

        static UUID uuidOf(PrivacyCluster cluster) {
            return cluster.instance().getCluster().getLocalMember().getUuid();
        }

        static IMap<String, String> cardMap(PrivacyCluster cluster) {
            return cluster.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
        }

        /** Polls {@code condition} for at most {@code seconds}; the wait is on state, bounded, and never a fixed sleep. */
        static boolean await(int seconds, BooleanSupplier condition) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
            return true;
        }

        static boolean awaitMembers(PrivacyCluster cluster, int members) {
            return await(30, () -> cluster.instance().getCluster().getMembers().size() == members);
        }

        void closeAll() {
            for (PrivacyCluster cluster : clusters) {
                cluster.close();
            }
            clusters.clear();
            Hazelcast.shutdownAll();
        }
    }

    /** Two keyed members on one clock, each with its own recorded signals. */
    private record Pair(PrivacyCluster a, PrivacyCluster b, Fixture.Metrics metricsA, Fixture.Metrics metricsB,
                        RecordingLogger logA) {
    }

    private Pair pair(ClusterKeys keysA, ClusterKeys keysB) {
        String name = TestMembers.uniqueName("cards");
        int first = FreePorts.consecutive(2);
        List<String> both = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1));
        var metricsA = new Fixture.Metrics();
        var metricsB = new Fixture.Metrics();
        var logA = new RecordingLogger();
        ClusterSecuritySettings settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        PrivacyCluster a = fixture.keyed(name, first, both, keysA, settings, metricsA, logA, false);
        PrivacyCluster b = fixture.keyed(name, first + 1, both, keysB, settings, metricsB, new RecordingLogger(),
                false);
        assertThat(Fixture.awaitMembers(a, 2)).as("A sees B").isTrue();
        assertThat(Fixture.awaitMembers(b, 2)).as("B sees A").isTrue();
        return new Pair(a, b, metricsA, metricsB, logA);
    }

    private Pair pair() {
        return pair(TestMembers.KEYS, TestMembers.KEYS);
    }

    // ---- what a card holds ---------------------------------------------------------------------------------

    @Test
    void aCardOpensKnowingOnlyTheLiveMemberUuid() {
        Pair pair = pair();
        UUID uuid = Fixture.uuidOf(pair.a());
        // A reader with the keys and a member UUID, and nothing else, finds and opens the card.
        String key = new KeyedNames(TestMembers.KEYS).mapKey(PrivacyCluster.MEMBER_CARD_MAP, uuid.toString());
        String stored = Fixture.cardMap(pair.b()).get(key);
        assertThat(stored).isNotNull();
        Opened opened = Sealer.builder(TestMembers.KEYS, PrivacyMetrics.none()).build()
                .open(PrivacyCluster.MEMBER_CARD_MAP, key, stored);
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);

        Card card = Card.decode(opened.plaintext());
        assertThat(card).isNotNull();
        assertThat(card.member()).isEqualTo(uuid);
        assertThat(card.version()).isEqualTo(MemberCards.FORMAT_VERSION);
        assertThat(card.acceptedKeyIds()).containsExactly("test-cluster-1");
        assertThat(card.keyCheckValues()).containsOnlyKeys("test-cluster-1", "test-name-1");
        assertThat(card.keyCheckValues().get("test-cluster-1")).isEqualTo(
                KeyCheckValue.compute("test-cluster-1", TestMembers.KEYS.current().material()));
        assertThat(card.keyCheckValues().get("test-name-1")).isEqualTo(
                KeyCheckValue.compute("test-name-1", TestMembers.KEYS.nameKeyMaterial()));
        assertThat(card.highestPauseSequence()).isZero();
        assertThat(card.leaseEnd()).isEqualTo(fixture.clock.instant().plus(LEASE));
    }

    @Test
    void aCardMovedToAnotherMembersKeyDoesNotOpen() {
        Pair pair = pair();
        IMap<String, String> cards = Fixture.cardMap(pair.a());
        String keyA = pair.a().memberCards().orElseThrow().keyForTest(Fixture.uuidOf(pair.a()));
        String keyB = pair.a().memberCards().orElseThrow().keyForTest(Fixture.uuidOf(pair.b()));
        cards.set(keyB, cards.get(keyA));
        pair.a().memberCards().orElseThrow().tick();
        assertThat(pair.a().memberCards().orElseThrow().verifiedMembers()).containsExactly(Fixture.uuidOf(pair.a()));
        assertThat(pair.metricsA().count(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(1);
    }

    @Test
    void theCardsOfAClusterAreVerifiedAndTellTheRulesTheyFeed() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        cards.tick();
        assertThat(cards.verifiedMembers()).containsExactlyInAnyOrder(Fixture.uuidOf(pair.a()),
                Fixture.uuidOf(pair.b()));
        assertThat(cards.unverifiedCount()).isZero();
        assertThat(cards.acceptedKeyIdsOfAllVerified()).containsExactly("test-cluster-1");
        assertThat(cards.highestReportedPauseSequence()).isZero();
        assertThat(pair.a().securityStatus().serving()).isTrue();

        pair.b().memberCards().orElseThrow().reportPauseSequence(() -> 7L);
        pair.b().memberCards().orElseThrow().publishNow();
        cards.tick();
        assertThat(cards.highestReportedPauseSequence()).isEqualTo(7L);
    }

    @Test
    void aCardIsRefreshedWhenHalfItsLeaseIsSpent() {
        Pair pair = pair();
        String key = pair.a().memberCards().orElseThrow().keyForTest(Fixture.uuidOf(pair.a()));
        String first = Fixture.cardMap(pair.a()).get(key);

        fixture.clock.advance(LEASE.dividedBy(2).minusSeconds(1));
        pair.a().memberCards().orElseThrow().tick();
        assertThat(Fixture.cardMap(pair.a()).get(key)).as("less than half spent").isEqualTo(first);

        fixture.clock.advance(Duration.ofSeconds(1));
        pair.a().memberCards().orElseThrow().tick();
        assertThat(Fixture.cardMap(pair.a()).get(key)).as("half spent").isNotEqualTo(first);
    }

    @Test
    void aPurposeKeyAddedLaterIsCarriedByTheNextCard() {
        Pair pair = pair();
        pair.a().memberCards().orElseThrow().addPurposeKey("test-link-1", Fixture.filled('l'));
        MemberCards reader = pair.b().memberCards().orElseThrow();
        String key = reader.keyForTest(Fixture.uuidOf(pair.a()));
        Opened opened = Sealer.builder(TestMembers.KEYS, PrivacyMetrics.none()).build()
                .open(PrivacyCluster.MEMBER_CARD_MAP, key, Fixture.cardMap(pair.b()).get(key));
        assertThat(Card.decode(opened.plaintext()).keyCheckValues()).containsKey("test-link-1");
        assertThat(Card.decode(opened.plaintext()).keyCheckValues().get("test-link-1"))
                .isEqualTo(KeyCheckValue.compute("test-link-1", Fixture.filled('l')));
    }

    @Test
    void theCardMapPassesItsBoundaryCheckAndTheCheckBites() {
        Pair pair = pair();
        var inputs = new Inputs(new KeyedNames(TestMembers.KEYS), null, null, null, null, null, null, null, null,
                null);
        IMap<Object, Object> contents = pair.a().instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
        var boundary = new MemberCardMapsBoundary();
        assertThat(contents.size()).isEqualTo(3);
        boundary.check(PrivacyCluster.MEMBER_CARD_MAP, contents, inputs);

        // A value that does not open, a sentinel anywhere but the reserved key, and a card under a foreign key.
        String sentinel = (String) contents.get(ClusterSentinel.KEY);
        String card = (String) contents.get(pair.a().memberCards().orElseThrow().keyForTest(Fixture.uuidOf(pair.a())));
        contents.put("planted", "not an envelope");
        assertThatThrownBy(() -> boundary.check(PrivacyCluster.MEMBER_CARD_MAP, contents, inputs))
                .isInstanceOf(AssertionError.class);
        contents.remove("planted");
        contents.put("planted", sentinel);
        assertThatThrownBy(() -> boundary.check(PrivacyCluster.MEMBER_CARD_MAP, contents, inputs))
                .isInstanceOf(AssertionError.class);
        contents.remove("planted");
        contents.put("planted", card);
        assertThatThrownBy(() -> boundary.check(PrivacyCluster.MEMBER_CARD_MAP, contents, inputs))
                .isInstanceOf(AssertionError.class);
        contents.remove("planted");
        boundary.check(PrivacyCluster.MEMBER_CARD_MAP, contents, inputs);
    }

    // ---- replay, deletion, strangers -------------------------------------------------------------------------

    @Test
    void aReplayedOldCardIsAcceptedOnlyUntilItsLeaseEnds() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        UUID memberB = Fixture.uuidOf(pair.b());
        String key = cards.keyForTest(memberB);
        String old = Fixture.cardMap(pair.a()).get(key);

        fixture.clock.advance(Duration.ofSeconds(40));
        pair.b().memberCards().orElseThrow().tick();
        cards.tick();
        assertThat(Fixture.cardMap(pair.a()).get(key)).as("B refreshed its card").isNotEqualTo(old);

        Fixture.cardMap(pair.a()).set(key, old);
        cards.tick();
        assertThat(cards.verifiedMembers()).as("inside the replayed card's lease").contains(memberB);

        fixture.clock.advance(Duration.ofSeconds(26));
        cards.tick();
        assertThat(cards.verifiedMembers()).as("past the replayed card's lease").doesNotContain(memberB);
        assertThat(cards.unverifiedCount()).isEqualTo(1);
    }

    @Test
    void aDeletedCardMakesTheMemberUnverifiedAfterTheGrace() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        Fixture.cardMap(pair.a()).delete(cards.keyForTest(Fixture.uuidOf(pair.b())));

        cards.tick();
        assertThat(cards.unverifiedCount()).isEqualTo(1);
        assertThat(pair.a().securityStatus().serving()).as("inside the grace").isTrue();

        fixture.clock.advance(GRACE.minusSeconds(1));
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).as("one second before the grace ends").isTrue();

        fixture.clock.advance(Duration.ofSeconds(1));
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).as("the grace is over").isFalse();
        assertThat(pair.a().securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER);
    }

    @Test
    void aCardForAUuidThatIsNotAMemberIsIgnored() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        UUID stranger = UUID.randomUUID();
        Card card = new Card(stranger, MemberCards.FORMAT_VERSION, List.of("test-cluster-1"), Map.of(), 0L,
                fixture.clock.instant().plus(LEASE));
        Fixture.cardMap(pair.a()).set(cards.keyForTest(stranger), cards.sealForTest(stranger, card));

        cards.tick();
        assertThat(cards.verifiedMembers()).doesNotContain(stranger).hasSize(2);
        assertThat(cards.unverifiedCount()).isZero();
        assertThat(pair.a().securityStatus().memberCount()).isEqualTo(2);
    }

    @Test
    void aCardSealedForAnotherUuidUnderThisMembersKeyIsNotThisMembers() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        UUID memberB = Fixture.uuidOf(pair.b());
        // An authentic card that names a different member, written under B's key.
        Card named = new Card(UUID.randomUUID(), MemberCards.FORMAT_VERSION, List.of("test-cluster-1"), Map.of(), 0L,
                fixture.clock.instant().plus(LEASE));
        Fixture.cardMap(pair.a()).set(cards.keyForTest(memberB), cards.sealForTest(memberB, named));

        cards.tick();
        assertThat(cards.verifiedMembers()).doesNotContain(memberB);
        assertThat(cards.unverifiedCount()).isEqualTo(1);
    }

    @Test
    void aForgedCardIsOneTamperCountHoweverOftenItIsLookedAt() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        Fixture.cardMap(pair.a()).set(cards.keyForTest(Fixture.uuidOf(pair.b())), "dps1.test-cluster-1.not.a.card");

        cards.tick();
        cards.tick();
        cards.tick();
        assertThat(cards.unverifiedCount()).isEqualTo(1);
        assertThat(pair.metricsA().count(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(1);
    }

    // ---- review round: values that are not cards, locked keys, versions, skew ------------------------------

    /** A member without the key puts a non-String under every card key: the next look must not keep "serving". */
    @Test
    void aNonStringCardIsUnverifiedAndTheMemberStopsServingOnceTheGraceIsOver() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        IMap<String, Object> raw = pair.b().instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
        for (UUID member : List.of(Fixture.uuidOf(pair.a()), Fixture.uuidOf(pair.b()))) {
            raw.put(cards.keyForTest(member), 42);
        }

        cards.tick();
        assertThat(pair.a().securityStatus().unverifiedCount()).isEqualTo(2);
        fixture.clock.advance(GRACE.multipliedBy(4));
        cards.tick();

        // A's tick republished its own card; B's is still the planted 42.
        assertThat(pair.a().securityStatus().serving()).isFalse();
        assertThatThrownBy(() -> new HazelcastScopeBudget(pair.a()).tryRead("CASE-1", "subject-1", 3))
                .isInstanceOf(ClusterOperationRefusedException.class);
    }

    @Test
    void aFailedLookNeverKeepsTheLastStatus() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).isTrue();

        cards.lookFault = () -> {
            throw new IllegalStateException("the cluster could not be read");
        };
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).isFalse();
        assertThatThrownBy(pair.a()::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);

        cards.lookFault = () -> { };
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).as("a good look serves again").isTrue();
    }

    /** A member that locks the card key of another must not stall its refresh, its status or its close. */
    @Test
    @Timeout(120)
    void aLockedCardKeyDoesNotBlockTheRefreshAndTheMemberStopsServing() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        IMap<String, String> lockerView = Fixture.cardMap(pair.b());
        String keyA = cards.keyForTest(Fixture.uuidOf(pair.a()));
        lockerView.lock(keyA);

        fixture.clock.advance(GRACE.multipliedBy(4));
        pair.b().memberCards().orElseThrow().tick();
        long started = System.nanoTime();
        cards.tick();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the tick is bounded")
                .isLessThan(Duration.ofSeconds(30));

        assertThat(pair.a().securityStatus().serving()).isFalse();
        assertThatThrownBy(() -> new HazelcastScopeBudget(pair.a()).tryRead("CASE-1", "subject-1", 3))
                .isInstanceOf(ClusterOperationRefusedException.class);
        pair.a().close();
    }

    @Test
    void anAuthenticCardOfAnotherFormatVersionIsNotAVerifiedCard() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        UUID memberB = Fixture.uuidOf(pair.b());
        Card other = new Card(memberB, MemberCards.FORMAT_VERSION + 1, List.of("test-cluster-1"), Map.of(), 0L,
                fixture.clock.instant().plus(LEASE));
        Fixture.cardMap(pair.a()).set(cards.keyForTest(memberB), cards.sealForTest(memberB, other));

        cards.tick();
        assertThat(cards.verifiedMembers()).doesNotContain(memberB);
        assertThat(cards.unverifiedCount()).isEqualTo(1);
    }

    @Test
    void aReaderWhoseClockRunsAheadByLessThanTheSkewToleranceKeepsAHealthyMember() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        UUID memberB = Fixture.uuidOf(pair.b());
        Duration skew = ClusterSecuritySettings.DEFAULT_CLOCK_SKEW_TOLERANCE;

        // B's card runs out at LEASE; the reader is ahead by just under the tolerance.
        fixture.clock.advance(LEASE.plus(skew).minusSeconds(1));
        cards.tick();
        assertThat(cards.verifiedMembers()).as("inside lease plus tolerance").contains(memberB);

        fixture.clock.advance(Duration.ofSeconds(1));
        cards.tick();
        assertThat(cards.verifiedMembers()).as("past lease plus tolerance").doesNotContain(memberB);
    }

    // ---- disagreeing keys ------------------------------------------------------------------------------------

    @Test
    void aCardWithADifferentKeyCheckValueCountsTamperAndStopsServing() {
        // Both hold current k1 (so each opens the other's card) and a previous key under the id k0 with
        // different material: the card's check value for k0 disagrees with the reader's.
        Pair pair = pair(Fixture.keys("test-cluster-1", 'c', "k0", 'x'), Fixture.keys("test-cluster-1", 'c', "k0", 'y'));
        MemberCards cards = pair.a().memberCards().orElseThrow();

        cards.tick();
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).isFalse();
        assertThat(pair.a().securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.KEY_CHECK_MISMATCH);
        assertThat(pair.metricsA().count(Metric.CLUSTER_SEAL_TAMPER)).as("counted once").isEqualTo(1);
        assertThatThrownBy(pair.a()::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);
    }

    // ---- a duplicate live UUID --------------------------------------------------------------------------------

    /** Starts a plain member with a chosen UUID, as an attacker with the jar would (spike Q6). */
    private static HazelcastInstance startWithUuid(Config config, UUID uuid) {
        return HazelcastInstanceFactory.newHazelcastInstance(config, null, new DefaultNodeContext() {
            @Override
            public NodeExtension createNodeExtension(Node node) {
                return new DefaultNodeExtension(node) {
                    @Override
                    public UUID createMemberUuid() {
                        return uuid;
                    }
                };
            }
        });
    }

    /**
     * Task 219 answer 6: a member that joins with the UUID of a live member does not join; each takes the other for
     * itself and both stay clusters of one. So the live member list never holds the UUID twice, and a card for that
     * UUID is the one live member's card, counted once.
     */
    @Test
    void aDuplicateLiveUuidNeverJoinsAndIsNeverACountedAsASecondVerifiedMember() throws Exception {
        String name = TestMembers.uniqueName("dupe");
        int first = FreePorts.consecutive(2);
        List<String> both = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1));
        PrivacyCluster a = fixture.keyed(name, first, both, TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(), new RecordingLogger(), false);
        CountDownLatch joined = new CountDownLatch(1);
        a.instance().getCluster().addMembershipListener(new MembershipListener() {
            @Override
            public void memberAdded(MembershipEvent event) {
                joined.countDown();
            }

            @Override
            public void memberRemoved(MembershipEvent event) {
            }
        });
        Config impostorConfig = new Config().setClusterName(name);
        impostorConfig.getJetConfig().setEnabled(false);
        impostorConfig.setProperty("hazelcast.phone.home.enabled", "false");
        impostorConfig.getNetworkConfig().setPort(first + 1).setPortAutoIncrement(false);
        impostorConfig.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        impostorConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        impostorConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).setMembers(both);
        impostorConfig.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        impostorConfig.setProperty("hazelcast.socket.bind.any", "false");
        HazelcastInstance impostor = startWithUuid(impostorConfig, Fixture.uuidOf(a));
        try {
            assertThat(joined.await(5, TimeUnit.SECONDS)).as("the impostor never joins").isFalse();
            MemberCards cards = a.memberCards().orElseThrow();
            cards.tick();
            assertThat(a.instance().getCluster().getMembers()).hasSize(1);
            assertThat(cards.verifiedMembers()).containsExactly(Fixture.uuidOf(a));
            assertThat(a.securityStatus().memberCount()).isEqualTo(1);
            assertThat(a.securityStatus().unverifiedCount()).isZero();
        } finally {
            impostor.shutdown();
        }
    }

    @Test
    void theStatusTextNamesNoUuidNoAddressAndNoKey() {
        Pair pair = pair();
        Fixture.cardMap(pair.a()).delete(pair.a().memberCards().orElseThrow().keyForTest(Fixture.uuidOf(pair.b())));
        fixture.clock.advance(GRACE);
        pair.a().memberCards().orElseThrow().tick();
        String text = pair.a().securityStatus().text() + " " + pair.a().securityStatus();
        assertThat(text).contains("not serving");
        assertThat(text).doesNotContain(Fixture.uuidOf(pair.a()).toString()).doesNotContain(Fixture.uuidOf(pair.b()).toString())
                .doesNotContain("127.0.0.1").doesNotContain("test-cluster-1").doesNotContain("test-name-1");
    }
}
