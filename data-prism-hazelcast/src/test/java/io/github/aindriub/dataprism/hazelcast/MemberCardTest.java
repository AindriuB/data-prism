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
import com.hazelcast.internal.partition.PartitionStateVersionMismatchException;
import com.hazelcast.map.EntryProcessor;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
                    PrivacyCluster.DEFAULT_CARD_WRITE_TIMEOUT);
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
        assertThat(TestMembers.settled(30, a, b)).as("the members did not settle").isTrue();
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

    // ---- review round 2: each half of the staleness check, ForkJoin threads, piled-up writes -----------------

    /** A look that never finishes: the member stops serving once a lease has gone by without one. */
    @Test
    @Timeout(120)
    void aLookThatHangsLeavesTheMemberNotServingAfterALease() throws Exception {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(40));
        cards.publishNow();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        cards.lookFault = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread ticking = new Thread(cards::tick);
        ticking.start();
        try {
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.a().securityStatus().serving()).as("the card is fresh, the look is not old yet").isTrue();
            fixture.clock.advance(Duration.ofSeconds(20));
            assertThat(pair.a().securityStatus().serving()).as("no look for a lease").isFalse();
            assertThat(pair.a().securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE);
        } finally {
            release.countDown();
            ticking.join();
        }
    }

    /** The own card is still readable (inside the skew tolerance) but could not be refreshed for a lease. */
    @Test
    @Timeout(120)
    void aFailedPublishLeavesTheMemberNotServingEvenWhileItsOwnCardIsStillReadable() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        Fixture.cardMap(pair.b()).lock(cards.keyForTest(Fixture.uuidOf(pair.a())));

        fixture.clock.advance(LEASE.plusSeconds(1));
        cards.tick();

        assertThat(cards.verifiedMembers()).as("its own card is still readable").contains(Fixture.uuidOf(pair.a()));
        assertThat(pair.a().securityStatus().serving()).isFalse();
        assertThat(pair.a().securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE);
    }

    /** On a ForkJoinPool worker a plain future wait would be unbounded. */
    @Test
    @Timeout(120)
    void aTickOnACommonPoolThreadIsBoundedWhileTheCardKeyIsLocked() throws Exception {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        Fixture.cardMap(pair.b()).lock(cards.keyForTest(Fixture.uuidOf(pair.a())));
        fixture.clock.advance(LEASE);

        CompletableFuture.runAsync(cards::tick).get(30, TimeUnit.SECONDS);
        assertThat(pair.a().securityStatus().serving()).isFalse();
    }

    /** Writes to a locked key queue at the owner: no new one is started while the last is outstanding. */
    @Test
    @Timeout(240)
    void atMostOneCardWriteIsOutstandingWhileTheCardKeyIsLocked() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        IMap<String, String> locker = Fixture.cardMap(pair.b());
        String keyA = cards.keyForTest(Fixture.uuidOf(pair.a()));
        locker.lock(keyA);
        fixture.clock.advance(LEASE.dividedBy(2));
        int before = cards.writesStarted();

        for (int i = 0; i < 11; i++) {
            cards.tick();
        }
        assertThat(cards.writesStarted() - before).as("writes started under the lock").isEqualTo(1);
        assertThat(cards.writeOutstanding()).isTrue();

        locker.unlock(keyA);
        assertThat(Fixture.await(30, () -> !cards.writeOutstanding())).as("the queued write completed").isTrue();
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).as("the next tick publishes again").isTrue();
    }

    // ---- two wrappers of one instance ----------------------------------------------------------------------

    /** Two wrappers share one member and one registered sealer; the sealer's gate is the instance's, not the first's. */
    private PrivacyCluster[] twoWrappers() {
        String name = TestMembers.uniqueName("wrappers");
        int port = FreePorts.consecutive(1);
        Config config = PrivacyCluster.configure(ClusterMembership.tcpIp(name, List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig());
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        var environment = fixture.environment(new Fixture.Metrics(), new RecordingLogger());
        var settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        PrivacyCluster first = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                settings, environment);
        PrivacyCluster second = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                settings, environment);
        return new PrivacyCluster[] {first, second};
    }

    private static Sealer registeredSealer(PrivacyCluster wrapper) {
        return (Sealer) wrapper.instance().getUserContext().get("dataprism.cluster.sealer");
    }

    @Test
    @Timeout(120)
    void closingTheFirstWrapperOfAnInstanceLeavesTheSecondServingAndItsSealedOperationsWorking() {
        PrivacyCluster[] wrappers = twoWrappers();
        Sealer registered = registeredSealer(wrappers[0]);
        assertThat(registered.seal(PrivacyCluster.IDENTITY_MAP, "k", new byte[] {1})).isNotNull();

        wrappers[0].close();
        fixture.clock.advance(LEASE.plusSeconds(1));
        wrappers[1].memberCards().orElseThrow().tick();

        assertThat(wrappers[1].securityStatus().serving()).isTrue();
        assertThat(registered.seal(PrivacyCluster.IDENTITY_MAP, "k", new byte[] {1}))
                .as("the sealer registered by the first wrapper").isNotNull();
        wrappers[1].close();
    }

    @Test
    @Timeout(120)
    void closingTheLastWrapperOfAnInstanceClosesTheGate() {
        PrivacyCluster[] wrappers = twoWrappers();
        Sealer registered = registeredSealer(wrappers[0]);
        wrappers[0].close();
        wrappers[1].close();

        assertThatThrownBy(() -> registered.seal(PrivacyCluster.IDENTITY_MAP, "k", new byte[] {1}))
                .isInstanceOf(ClusterOperationRefusedException.class);
    }

    // ---- review round 3 ---------------------------------------------------------------------------------------

    /** A wrapper that is still starting is not a serving owner of the instance's registered sealer. */
    @Test
    @Timeout(240)
    void aWrapperThatIsStillStartingDoesNotOpenTheInstanceGate() throws Exception {
        int port = FreePorts.consecutive(2);
        List<String> everyone = List.of("127.0.0.1:" + port, "127.0.0.1:" + (port + 1));
        Config config = PrivacyCluster.configure(ClusterMembership.tcpIp(TestMembers.uniqueName("starting"), everyone)
                .withPort(port).withInterface("127.0.0.1").toConfig());
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        var environment = fixture.environment(new Fixture.Metrics(), new RecordingLogger());
        var settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        PrivacyCluster first = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                settings, environment);
        Sealer registered = registeredSealer(first);
        var blockedEnvironment = shortWriteEnvironment();
        HazelcastInstance outsider = Fixture.plain(config.getClusterName(), port + 1, everyone);
        // The outsider holds this member's card key, so the next wrapper's first card write waits and then fails.
        String cardKey = first.memberCards().orElseThrow().keyForTest(instance.getCluster().getLocalMember().getUuid());
        outsider.getMap(PrivacyCluster.MEMBER_CARD_MAP).lock(cardKey);
        first.close();

        CompletableFuture<Object> second = CompletableFuture.supplyAsync(() -> {
            try {
                return PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS, settings,
                        blockedEnvironment);
            } catch (RuntimeException failed) {
                return failed;
            }
        });
        while (!second.isDone()) {
            assertThatThrownBy(() -> registered.seal(PrivacyCluster.IDENTITY_MAP, "k", new byte[] {1}))
                    .as("while the second wrapper is starting").isInstanceOf(ClusterOperationRefusedException.class);
        }
        assertThat(second.get()).as("its first card write failed").isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> registered.seal(PrivacyCluster.IDENTITY_MAP, "k", new byte[] {1}))
                .isInstanceOf(ClusterOperationRefusedException.class);
    }

    @Test
    void aClosedWrapperRefusesAtOnce() {
        PrivacyCluster[] wrappers = twoWrappers();
        assertThat(wrappers[0].servingInstance()).isNotNull();
        wrappers[0].close();
        assertThatThrownBy(wrappers[0]::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(wrappers[1].servingInstance()).isNotNull();
    }

    /** The sentinel is rewritten while absent; under a lock the writes must not pile up at the owner. */
    @Test
    @Timeout(240)
    void atMostOneSentinelWriteIsOutstandingWhileTheSentinelKeyIsLockedAndDeleted() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        IMap<String, Object> lockerView = pair.b().instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
        lockerView.lock(ClusterSentinel.KEY);
        lockerView.delete(ClusterSentinel.KEY);
        int before = cards.sentinelWritesStarted();

        for (int i = 0; i < 8; i++) {
            cards.tick();
        }
        assertThat(cards.sentinelWritesStarted() - before).isEqualTo(1);
        assertThat(cards.sentinelWriteOutstanding()).isTrue();
        assertThat(pair.a().securityStatus().serving()).as("unknown is not serving").isFalse();

        lockerView.unlock(ClusterSentinel.KEY);
        assertThat(Fixture.await(30, () -> !cards.sentinelWriteOutstanding())).isTrue();
        cards.tick();
        assertThat(pair.a().securityStatus().serving()).isTrue();
    }

    /** A key added while a card write is under way is not in that card: the next tick must publish again. */
    @Test
    void aPurposeKeyAddedDuringACardWriteIsPublishedByTheNextTick() {
        Pair pair = pair();
        MemberCards cards = pair.a().memberCards().orElseThrow();
        UUID memberA = Fixture.uuidOf(pair.a());
        String key = cards.keyForTest(memberA);
        boolean[] added = new boolean[1];
        cards.afterCardBuilt = () -> {
            if (!added[0]) {
                added[0] = true;
                cards.addPurposeKey("test-link-1", Fixture.filled('l'));
            }
        };

        fixture.clock.advance(LEASE.dividedBy(2));
        cards.tick();
        cards.afterCardBuilt = () -> { };
        cards.tick();

        Opened opened = Sealer.builder(TestMembers.KEYS, PrivacyMetrics.none()).build()
                .open(PrivacyCluster.MEMBER_CARD_MAP, key, Fixture.cardMap(pair.b()).get(key));
        assertThat(Card.decode(opened.plaintext()).keyCheckValues()).containsKey("test-link-1");
    }

    // ---- review round 4: one gate and one card for an instance ------------------------------------------------

    private Card cardOf(PrivacyCluster wrapper) {
        UUID member = wrapper.instance().getCluster().getLocalMember().getUuid();
        String key = new KeyedNames(TestMembers.KEYS).mapKey(PrivacyCluster.MEMBER_CARD_MAP, member.toString());
        String stored = (String) wrapper.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP).get(key);
        Opened opened = Sealer.builder(TestMembers.KEYS, PrivacyMetrics.none()).build()
                .open(PrivacyCluster.MEMBER_CARD_MAP, key, stored);
        return Card.decode(opened.plaintext());
    }

    /** Two wrappers with different policies and an outsider past both graces: there is one gate, and it is closed. */
    @Test
    @Timeout(240)
    void theStoresOfEveryWrapperRefuseWheneverTheInstanceGateRefuses() {
        int port = FreePorts.consecutive(2);
        List<String> everyone = List.of("127.0.0.1:" + port, "127.0.0.1:" + (port + 1));
        Config config = PrivacyCluster.configure(ClusterMembership.tcpIp(TestMembers.uniqueName("policies"), everyone)
                .withPort(port).withInterface("127.0.0.1").toConfig());
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        var environment = fixture.environment(new Fixture.Metrics(), new RecordingLogger());
        PrivacyCluster refusing = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), environment);
        PrivacyCluster alerting = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.ALERT), environment);
        HazelcastInstance outsider = Fixture.plain(config.getClusterName(), port + 1, everyone);
        assertThat(Fixture.awaitMembers(refusing, 2)).isTrue();
        // The partitions move to the joiner after it joined; do not look while they still do.
        assertThat(Fixture.await(30, () -> instance.getPartitionService().isClusterSafe())).isTrue();

        for (PrivacyCluster wrapper : List.of(refusing, alerting)) {
            wrapper.memberCards().orElseThrow().tick();
        }
        fixture.clock.advance(GRACE.plusSeconds(1));
        for (PrivacyCluster wrapper : List.of(refusing, alerting)) {
            wrapper.memberCards().orElseThrow().tick();
        }

        assertThat(alerting.securityStatus().serving()).as("its own policy alone would serve").isTrue();
        for (PrivacyCluster wrapper : List.of(refusing, alerting)) {
            assertThatThrownBy(() -> new HazelcastScopeBudget(wrapper).tryRead("CASE-1", "subject-1", 3))
                    .isInstanceOf(ClusterOperationRefusedException.class);
            assertThatThrownBy(wrapper::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);
        }
        outsider.shutdown();
        assertThat(Fixture.awaitMembers(refusing, 1)).isTrue();
        for (PrivacyCluster wrapper : List.of(refusing, alerting)) {
            wrapper.memberCards().orElseThrow().tick();
        }
        assertThat(refusing.servingInstance()).isNotNull();
        assertThat(alerting.servingInstance()).isNotNull();
    }

    @Test
    void aWrapperThatHasNotStartedRefusesNextToOneThatServes() {
        PrivacyCluster[] wrappers = twoWrappers();
        PrivacyCluster unstarted = PrivacyCluster.wrap(wrappers[0].instance(), false, Duration.ofMinutes(1),
                TestMembers.KEYS, false);
        assertThat(wrappers[0].servingInstance()).isNotNull();
        assertThatThrownBy(unstarted::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);
        unstarted.close();
    }

    @Test
    @Timeout(240)
    void thePurposeKeysOfAllWrappersAreOnTheOneCardAndNoRefreshDropsOne() {
        PrivacyCluster[] wrappers = twoWrappers();
        wrappers[0].memberCards().orElseThrow().addPurposeKey("test-link-a", Fixture.filled('a'));
        wrappers[1].memberCards().orElseThrow().addPurposeKey("test-link-b", Fixture.filled('b'));
        assertThat(cardOf(wrappers[0]).keyCheckValues()).containsKeys("test-link-a", "test-link-b");

        for (int refresh = 0; refresh < 2; refresh++) {
            for (PrivacyCluster wrapper : wrappers) {
                fixture.clock.advance(LEASE.dividedBy(2));
                wrapper.memberCards().orElseThrow().tick();
                assertThat(cardOf(wrapper).keyCheckValues()).as("after a refresh by a wrapper")
                        .containsKeys("test-link-a", "test-link-b");
            }
        }
    }

    @Test
    void theReportedPauseSequenceOfAnInstanceNeverGoesDown() {
        PrivacyCluster[] wrappers = twoWrappers();
        long[] first = {7L};
        wrappers[0].memberCards().orElseThrow().reportPauseSequence(() -> first[0]);
        wrappers[1].memberCards().orElseThrow().reportPauseSequence(() -> 3L);

        wrappers[1].memberCards().orElseThrow().publishNow();
        assertThat(cardOf(wrappers[1]).highestPauseSequence()).as("the other wrapper's refresh").isEqualTo(7L);
        first[0] = 2L;
        wrappers[0].memberCards().orElseThrow().publishNow();
        assertThat(cardOf(wrappers[0]).highestPauseSequence()).as("a lower report").isEqualTo(7L);
        wrappers[0].close();
        wrappers[1].memberCards().orElseThrow().publishNow();
        assertThat(cardOf(wrappers[1]).highestPauseSequence()).as("after the reporter closed").isEqualTo(7L);
    }

    @Test
    @Timeout(240)
    void closingOneWrapperKeepsTheDeclarationsOfTheOtherAndClosingTheLastStopsTheCard() {
        PrivacyCluster[] wrappers = twoWrappers();
        wrappers[0].memberCards().orElseThrow().addPurposeKey("test-link-a", Fixture.filled('a'));
        wrappers[1].memberCards().orElseThrow().addPurposeKey("test-link-b", Fixture.filled('b'));

        wrappers[0].close();
        wrappers[1].memberCards().orElseThrow().publishNow();
        assertThat(cardOf(wrappers[1]).keyCheckValues()).containsKey("test-link-b").doesNotContainKey("test-link-a");

        MemberCards last = wrappers[1].memberCards().orElseThrow();
        int writes = last.writesStarted();
        wrappers[1].close();
        fixture.clock.advance(LEASE);
        last.tick();
        assertThat(last.writesStarted()).as("the card is no longer refreshed").isEqualTo(writes);
    }

    // ---- review round 5: pruning, the order of registration, the initial status --------------------------------

    /** Outsiders that come with fresh UUIDs and leave garbage must not be remembered for ever. */
    @Test
    @Timeout(240)
    void whatIsRememberedAboutDepartedMembersIsPruned() {
        int port = FreePorts.consecutive(4);
        List<String> everyone = List.of("127.0.0.1:" + port, "127.0.0.1:" + (port + 1), "127.0.0.1:" + (port + 2),
                "127.0.0.1:" + (port + 3));
        PrivacyCluster a = fixture.keyed(TestMembers.uniqueName("pruning"), port, everyone, TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.ALERT), new Fixture.Metrics(), new RecordingLogger(), true);
        MemberCards cards = a.memberCards().orElseThrow();
        String clusterName = a.instance().getConfig().getClusterName();
        for (int round = 1; round <= 3; round++) {
            HazelcastInstance outsider = Fixture.plain(clusterName, port + round, everyone);
            assertThat(Fixture.awaitMembers(a, 2)).isTrue();
            String key = cards.keyForTest(outsider.getCluster().getLocalMember().getUuid());
            outsider.getMap(PrivacyCluster.MEMBER_CARD_MAP).put(key, "x".repeat(10_000));
            cards.tick();
            assertThat(cards.rejectedMembers()).as("while it is there").hasSize(1);
            outsider.shutdown();
            assertThat(Fixture.awaitMembers(a, 1)).isTrue();
            cards.tick();
            Fixture.cardMap(a).delete(key);
        }
        assertThat(cards.rejectedMembers()).as("only live members are remembered").isEmpty();
    }

    @Test
    void theCountedDisagreementsOfADepartedMemberAreForgotten() {
        Pair pair = pair(Fixture.keys("test-cluster-1", 'c', "k0", 'x'), Fixture.keys("test-cluster-1", 'c', "k0", 'y'));
        MemberCards cards = pair.a().memberCards().orElseThrow();
        cards.tick();
        assertThat(cards.mismatchesCountedSize()).isEqualTo(1);

        pair.b().close();
        assertThat(Fixture.awaitMembers(pair.a(), 1)).isTrue();
        cards.tick();
        assertThat(cards.mismatchesCountedSize()).isZero();
    }

    /** A wrapper whose first card write is blocked is not yet an owner of the gate: the serving one decides. */
    @Test
    @Timeout(240)
    void aWrapperBlockedInStartDoesNotCloseTheGateOfAServingWrapper() throws Exception {
        int port = FreePorts.consecutive(2);
        List<String> everyone = List.of("127.0.0.1:" + port, "127.0.0.1:" + (port + 1));
        Config config = PrivacyCluster.configure(ClusterMembership.tcpIp(TestMembers.uniqueName("blocked"), everyone)
                .withPort(port).withInterface("127.0.0.1").toConfig());
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        var environment = fixture.environment(new Fixture.Metrics(), new RecordingLogger());
        var settings = Fixture.settings(UnverifiedMemberAction.ALERT);
        PrivacyCluster serving = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                settings, environment);
        Sealer registered = registeredSealer(serving);
        var blockedEnvironment = shortWriteEnvironment();
        HazelcastInstance outsider = Fixture.plain(config.getClusterName(), port + 1, everyone);
        String cardKey = serving.memberCards().orElseThrow()
                .keyForTest(instance.getCluster().getLocalMember().getUuid());
        outsider.getMap(PrivacyCluster.MEMBER_CARD_MAP).lock(cardKey);

        CompletableFuture<Object> second = CompletableFuture.supplyAsync(() -> {
            try {
                return PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS, settings,
                        blockedEnvironment);
            } catch (RuntimeException failed) {
                return failed;
            }
        });
        while (!second.isDone()) {
            assertThat(registered.seal(PrivacyCluster.IDENTITY_MAP, "k", new byte[] {1}))
                    .as("while the second wrapper is blocked in start").isNotNull();
        }
        assertThat(second.get()).isInstanceOf(RuntimeException.class);
        assertThat(PrivacyCluster.liveWrapperCount(instance)).as("only the serving wrapper is live").isEqualTo(1);
    }

    // ---- a slow first write at start-up ------------------------------------------------------------------

    /** The write bound of the slow-start tests: far below how long the fault holds the key. */
    private static final Duration SHORT_WRITE_BOUND = Duration.ofMillis(300);
    /** How long the fault holds the key: well over the write bound, well under the start-up bound. */
    private static final long FAULT_MILLIS = 1500;

    private PrivacyCluster.Environment shortWriteEnvironment() {
        return new PrivacyCluster.Environment(fixture.clock, new Fixture.Metrics(), new RecordingLogger().logger(),
                Duration.ofHours(1), SHORT_WRITE_BOUND);
    }

    /** One member that holds no wrapper yet. */
    private HazelcastInstance loneInstance(String prefix, String backupTimeoutMillis) {
        int port = FreePorts.consecutive(1);
        Config config = PrivacyCluster.configure(ClusterMembership.tcpIp(TestMembers.uniqueName(prefix),
                List.of("127.0.0.1:" + port)).withPort(port).withInterface("127.0.0.1").toConfig());
        if (backupTimeoutMillis != null) {
            config.setProperty("hazelcast.operation.backup.timeout.millis", backupTimeoutMillis);
        }
        return Hazelcast.newHazelcastInstance(config);
    }

    /** Set when the write to the held key has been started, so a test can prove it began under the hold. */
    private final AtomicBoolean writeBeganUnderHold = new AtomicBoolean();

    @AfterEach
    void clearWriteHook() {
        ClusterSentinel.afterWriteStarted = key -> { };
    }

    /**
     * Holds {@code key} from another thread (a lock is owned by a thread, so the thread that starts the wrapper
     * would pass through its own lock), as a member that is mid-migration or holding a lock would, so that a write
     * to it does not finish. When {@code release}, the hold ends {@link #FAULT_MILLIS} after the write to the key
     * has actually been started, from the same thread, so the write begins under the hold however slow the host.
     */
    private ScheduledExecutorService holdKey(HazelcastInstance instance, String key, boolean release) {
        IMap<String, Object> cards = instance.getMap(PrivacyCluster.MEMBER_CARD_MAP);
        var holder = Executors.newSingleThreadScheduledExecutor();
        try {
            holder.submit(() -> cards.lock(key)).get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
        writeBeganUnderHold.set(false);
        ClusterSentinel.afterWriteStarted = written -> {
            if (written.equals(key) && writeBeganUnderHold.compareAndSet(false, true) && release) {
                holder.schedule(() -> cards.unlock(key), FAULT_MILLIS, TimeUnit.MILLISECONDS);
            }
        };
        return holder;
    }

    /**
     * A write that Hazelcast has not completed within the write bound (a lost backup acknowledgement during the
     * join-time migration completes only when Hazelcast's own backup timeout, 5 s by default, has gone by) must not
     * make a member refuse to start: the first card write is given Hazelcast's own bound on top.
     */
    @Test
    @Timeout(240)
    void aFirstCardWriteThatOutlastsTheWriteBoundButFinishesStillStartsTheMember() {
        HazelcastInstance instance = loneInstance("slowcard", null);
        String cardKey = new KeyedNames(TestMembers.KEYS).mapKey(PrivacyCluster.MEMBER_CARD_MAP,
                instance.getCluster().getLocalMember().getUuid().toString());
        var releaser = holdKey(instance, cardKey, true);
        try {
            PrivacyCluster member = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                    Fixture.settings(UnverifiedMemberAction.REFUSE), shortWriteEnvironment());

            assertThat(writeBeganUnderHold).as("the write began while the key was held").isTrue();
            assertThat(member.securityStatus().serving()).as("serving once its card is published and verified")
                    .isTrue();
            assertThat(member.memberCards().orElseThrow().verifiedMembers())
                    .containsExactly(instance.getCluster().getLocalMember().getUuid());
            member.close();
        } finally {
            releaser.shutdownNow();
        }
    }

    /** The same slow write, for the sentinel a first member writes before its card. */
    @Test
    @Timeout(240)
    void aSentinelWriteThatOutlastsTheWriteBoundButFinishesStillStartsTheMember() {
        HazelcastInstance instance = loneInstance("slowsentinel", null);
        var releaser = holdKey(instance, ClusterSentinel.KEY, true);
        try {
            PrivacyCluster member = PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                    Fixture.settings(UnverifiedMemberAction.REFUSE), shortWriteEnvironment());

            assertThat(writeBeganUnderHold).as("the write began while the key was held").isTrue();
            assertThat(member.securityStatus().serving()).isTrue();
            member.close();
        } finally {
            releaser.shutdownNow();
        }
    }

    /** The start-up bound is longer, not unbounded: a write that never finishes still refuses the member. */
    @Test
    @Timeout(240)
    void aFirstCardWriteThatNeverFinishesStillRefusesTheMemberAndItDoesNotServe() {
        HazelcastInstance instance = loneInstance("stuckcard", "500");
        String cardKey = new KeyedNames(TestMembers.KEYS).mapKey(PrivacyCluster.MEMBER_CARD_MAP,
                instance.getCluster().getLocalMember().getUuid().toString());
        var releaser = holdKey(instance, cardKey, false);
        try {
            assertThatThrownBy(() -> PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS,
                    Fixture.settings(UnverifiedMemberAction.REFUSE), shortWriteEnvironment()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("a member card write did not finish in time");
            assertThat(PrivacyCluster.liveWrapperCount(instance)).isZero();
        } finally {
            releaser.shutdownNow();
        }
    }

    /** The start-up bound is the write bound, plus Hazelcast's backup timeout from the member's own settings. */
    @Test
    void theStartUpWriteBoundIsTheWriteBoundPlusHazelcastsBackupTimeoutPlusASweep() {
        HazelcastInstance defaults = loneInstance("bounddefault", null);
        HazelcastInstance tuned = loneInstance("boundtuned", "1500");
        var environment = shortWriteEnvironment();
        PrivacyCluster first = PrivacyCluster.using(defaults, false, Duration.ofMinutes(1), TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), environment);
        PrivacyCluster second = PrivacyCluster.using(tuned, false, Duration.ofMinutes(1), TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), environment);

        Duration sweep = ClusterSentinel.BACKUP_SWEEP_ALLOWANCE;
        assertThat(first.memberCards().orElseThrow().startupWriteBound())
                .isEqualTo(SHORT_WRITE_BOUND.plusMillis(5000).plus(sweep));
        assertThat(second.memberCards().orElseThrow().startupWriteBound())
                .isEqualTo(SHORT_WRITE_BOUND.plusMillis(1500).plus(sweep));
        first.close();
        second.close();
    }

    /** A huge backup timeout neither overflows the bound nor makes it effectively unbounded; below zero is zero. */
    @Test
    void aHugeOrNegativeBackupTimeoutIsClampedInTheStartUpWriteBound() {
        Duration ceilingBound = SHORT_WRITE_BOUND.plus(ClusterSentinel.BACKUP_TIMEOUT_CEILING)
                .plus(ClusterSentinel.BACKUP_SWEEP_ALLOWANCE);
        Duration zeroBound = SHORT_WRITE_BOUND.plus(ClusterSentinel.BACKUP_SWEEP_ALLOWANCE);
        var environment = shortWriteEnvironment();
        for (var expected : Map.of(String.valueOf(Long.MAX_VALUE), ceilingBound, "1000000000000000", ceilingBound,
                "0", zeroBound, "-5", zeroBound).entrySet()) {
            PrivacyCluster member = PrivacyCluster.using(loneInstance("boundclamp", expected.getKey()), false,
                    Duration.ofMinutes(1), TestMembers.KEYS, Fixture.settings(UnverifiedMemberAction.REFUSE),
                    environment);
            assertThat(member.memberCards().orElseThrow().startupWriteBound()).as(expected.getKey())
                    .isEqualTo(expected.getValue());
            member.close();
        }
    }

    @Test
    void aWrapperThatHasNotStartedIsNotServing() {
        PrivacyCluster[] wrappers = twoWrappers();
        PrivacyCluster unstarted = PrivacyCluster.wrap(wrappers[0].instance(), false, Duration.ofMinutes(1),
                TestMembers.KEYS, false);
        assertThat(unstarted.memberCards().orElseThrow().status().serving()).isFalse();
        unstarted.close();
    }

    /** A look that is under way when the wrapper closes must not bring a closed wrapper back to serving. */
    @Test
    @Timeout(120)
    void aLookThatFinishesAfterCloseDoesNotMakeTheClosedWrapperServe() throws Exception {
        PrivacyCluster[] wrappers = twoWrappers();
        MemberCards cards = wrappers[0].memberCards().orElseThrow();
        CountDownLatch inLook = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        cards.lookFault = () -> {
            inLook.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread ticking = new Thread(cards::tick);
        ticking.start();
        try {
            assertThat(inLook.await(30, TimeUnit.SECONDS)).isTrue();
            wrappers[0].close();
        } finally {
            release.countDown();
            ticking.join();
        }
        assertThat(cards.status().serving()).isFalse();
        assertThatThrownBy(wrappers[0]::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);
    }

    // ---- the serialisation filter and a migration-time error -----------------------------------------------------

    /** Throws what a member answers with when the member list changed under a migration. */
    static final class ThrowsPartitionStateMismatch
            implements EntryProcessor<String, String, Object> {
        @Override
        public Object process(Map.Entry<String, String> entry) {
            throw new PartitionStateVersionMismatchException(3, 4);
        }
    }

    /**
     * Hazelcast answers a migration operation with this exception "when the cluster member list changes during
     * migration", and the master expects it (it then retries). It travels as a Java-serialised error reply, so a
     * filter that refuses it turns a prompt failure into a lost reply that lasts until the migration timeout.
     */
    @Test
    @Timeout(120)
    void aPartitionStateMismatchRaisedByAnotherMemberReachesTheCallerThroughTheFilter() {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("mismatch-filter"), OptionalInt.empty());
        try {
            assertThat(Fixture.await(30, () -> members[0].instance().getPartitionService().isClusterSafe())).isTrue();
            HazelcastInstance owner = members[1].instance();
            String[] key = new String[1];
            assertThat(Fixture.await(30, () -> {
                for (int i = 0; i < 10_000; i++) {
                    String candidate = "mismatch-" + i;
                    if (members[0].instance().getPartitionService().getPartition(candidate).getOwner()
                            .equals(owner.getCluster().getLocalMember())) {
                        key[0] = candidate;
                        return true;
                    }
                }
                return false;
            })).isTrue();

            IMap<String, String> map = members[0].instance().getMap(PrivacyCluster.IDENTITY_MAP);
            Throwable thrown = catchThrowable(() -> map.executeOnKey(key[0], new ThrowsPartitionStateMismatch()));

            boolean reached = false;
            for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
                reached |= cause instanceof PartitionStateVersionMismatchException;
            }
            assertThat(reached).as("the caller saw the owner's exception").isTrue();
        } finally {
            members[0].close();
            members[1].close();
        }
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
