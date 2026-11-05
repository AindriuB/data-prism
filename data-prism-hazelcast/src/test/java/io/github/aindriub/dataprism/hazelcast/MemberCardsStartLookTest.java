package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 298: a membership event during start() does not run a look beside the start thread's own look. */
class MemberCardsStartLookTest {

    private final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    private PrivacyCluster wrapper;
    private HazelcastInstance real;
    private MemberCards cards;

    @AfterEach
    void shutDown() {
        if (cards != null) {
            cards.close();
        }
        if (wrapper != null) {
            wrapper.close();
        }
        Hazelcast.shutdownAll();
    }

    private static byte[] filled(char c) {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) c);
        return out;
    }

    /** A loopback-only keyed wrapper over a listener-capturing instance, and a fresh, unstarted MemberCards on it. */
    private void setUp() {
        Config config = new Config().setClusterName(TestMembers.uniqueName("startlook"));
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(FreePorts.consecutive(1)).setPortAutoIncrement(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        var join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        real = Hazelcast.newHazelcastInstance(config);
        Cluster cluster = (Cluster) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Cluster.class}, (p, m, a) -> {
                    if (m.getName().equals("addMembershipListener")) {
                        listeners.add((MembershipListener) a[0]);
                    }
                    try {
                        return m.invoke(real.getCluster(), a);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        HazelcastInstance capturing = (HazelcastInstance) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {HazelcastInstance.class}, (p, m, a) -> {
                    if (m.getName().equals("getCluster")) {
                        return cluster;
                    }
                    try {
                        return m.invoke(real, a);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        ClusterKeys keys = new ClusterKeys("k-1", filled('c'), null, null, null, null, "n-1", filled('n'));
        wrapper = PrivacyCluster.using(capturing, false, keys);
        listeners.clear();
        var environment = new PrivacyCluster.Environment(Clock.systemUTC(), PrivacyMetrics.none(),
                LoggerFactory.getLogger(MemberCards.class), Duration.ofHours(1));
        Sealer sealer = Sealer.builder(keys, PrivacyMetrics.none()).build();
        cards = new MemberCards(wrapper, keys, ClusterSecuritySettings.defaults(), sealer,
                new ClusterSentinel(wrapper, keys, sealer, PrivacyCluster.DEFAULT_CARD_WRITE_TIMEOUT), environment);
    }

    private void memberJoins() {
        Member local = real.getCluster().getLocalMember();
        var event = new MembershipEvent(real.getCluster(), local, MembershipEvent.MEMBER_ADDED, Set.of(local));
        listeners.forEach(l -> l.memberAdded(event));
    }

    @Test
    @Timeout(120)
    void aMembershipEventDuringStartRunsNoSecondLook() throws Exception {
        setUp();
        AtomicInteger entries = new AtomicInteger();
        List<Thread> entrants = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        cards.lookFault = () -> {
            entrants.add(Thread.currentThread());
            if (entries.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Thread starting = new Thread(cards::start, "start-under-test");
        starting.start();
        try {
            assertThat(entered.await(60, TimeUnit.SECONDS)).as("the start look began").isTrue();
            assertThat(listeners).as("the listener was registered before the start look").isNotEmpty();
            memberJoins();
            cards.drainExecutorForTest();
            assertThat(entries.get()).as("lookFault entered once").isEqualTo(1);
            assertThat(entrants).extracting(Thread::getName).containsExactly("start-under-test");
        } finally {
            release.countDown();
            starting.join(TimeUnit.SECONDS.toMillis(60));
        }
        assertThat(starting.isAlive()).isFalse();
    }

    @Test
    @Timeout(120)
    void afterStartAMembershipEventLooksAndATickLooks() throws Exception {
        setUp();
        AtomicInteger entries = new AtomicInteger();
        cards.lookFault = entries::incrementAndGet;
        cards.start();
        int afterStart = entries.get();
        assertThat(afterStart).as("the start look").isEqualTo(1);
        memberJoins();
        cards.drainExecutorForTest();
        assertThat(entries.get()).as("the event looked").isGreaterThan(afterStart);
        int afterEvent = entries.get();
        cards.tick();
        assertThat(entries.get()).as("the tick looked").isGreaterThan(afterEvent);
    }
}
