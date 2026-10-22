package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The 0.7 to 0.8 cutover is non-rolling (cluster-security 4.8, OD-215-1 (a)): a 0.8 member that finds an entry in a
 * pre-0.8 map refuses to start, so a 0.7 and a 0.8 generation never serve side by side. The "0.7 member" here is a
 * plain Hazelcast member holding entries under the old names; it is started with phone-home off.
 */
class ClusterCutoverTest {

    private static final char NUL = 0;

    private final String cluster = TestMembers.uniqueName("cutover");
    private int oldPort;
    private int newPort;
    private HazelcastInstance old;

    @BeforeEach
    void startTheOldMember() {
        oldPort = FreePorts.consecutive(1);
        newPort = FreePorts.consecutive(1);
        Config config = new Config().setClusterName(cluster);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(oldPort).setPortAutoIncrement(false);
        var join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).addMember("127.0.0.1:" + newPort);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        old = Hazelcast.newHazelcastInstance(config);
    }

    @AfterEach
    void stop() {
        old.getLifecycleService().terminate();
    }

    private PrivacyCluster startNewMember() {
        return PrivacyCluster.embedded(ClusterMembership
                .tcpIp(cluster, List.of("127.0.0.1:" + oldPort, "127.0.0.1:" + newPort)).withPort(newPort)
                .withInterface("127.0.0.1"), false, TestMembers.KEYS);
    }

    private void clearOldMaps() {
        PrivacyCluster.PRE_0_8_MAPS.forEach(name -> old.getMap(name).clear());
    }

    @Test
    void aMemberThatJoinsAClusterHoldingAnEntryInAnyPreZeroEightMapIsRefusedAndServesNothing() {
        assertThat(PrivacyCluster.PRE_0_8_MAPS).hasSize(8);
        for (String map : PrivacyCluster.PRE_0_8_MAPS) {
            clearOldMaps();
            old.getMap(map).put("scope-a" + NUL + "subject-a", "old-format-value-7f3a");
            Throwable refused = catchThrowable(this::startNewMember);
            assertThat(refused).as(map).isInstanceOf(PrivacyClusterRefusal.class);
            assertThat(((PrivacyClusterRefusal) refused).code()).as(map).isEqualTo(Code.UNSEALED_CLUSTER_STATE);
            // Serves nothing: the member that was started is gone, and the old member is alone again.
            assertThat(await(30, () -> old.getCluster().getMembers().size() == 1)).as(map + " member left").isTrue();
            assertThat(Hazelcast.getAllHazelcastInstances()).as(map).containsExactly(old);
        }
    }

    @Test
    void aBudgetEntryWrittenInTheZeroSevenFormatIsRefused() {
        // 0.7: key = scope NUL subject, value = the Integer count.
        old.<String, Integer>getMap("dataprism.budget").put("scope-a" + NUL + "subject-a", 3);
        Throwable refused = catchThrowable(this::startNewMember);
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.UNSEALED_CLUSTER_STATE);
    }

    @Test
    void theRefusalNamesTheConditionAndNeverAnEntry() {
        old.getMap("dataprism.budget").put("scope-secret" + NUL + "subject-secret", 41);
        Throwable refused = catchThrowable(this::startNewMember);
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(refused.getMessage()).startsWith("UNSEALED_CLUSTER_STATE")
                .doesNotContain("scope-secret").doesNotContain("subject-secret").doesNotContain("41");
    }

    @Test
    void aClusterWithNoPreZeroEightEntryStartsAndCreatesTheS1MapsEmpty() {
        try (PrivacyCluster fresh = startNewMember()) {
            assertThat(await(30, () -> fresh.instance().getCluster().getMembers().size() == 2)).isTrue();
            assertThat(fresh.instance().getMap(PrivacyCluster.BUDGET_MAP).isEmpty()).isTrue();
        }
    }

    @Test
    void theFenceEntryAloneIsNotAnEntry() {
        // cluster-security 4.8: key `all` = Boolean.TRUE in the pre-0.8 oversight map is the fence, ignored.
        old.getMap("dataprism.oversight").put("all", Boolean.TRUE);
        try (PrivacyCluster fresh = startNewMember()) {
            assertThat(fresh.instance().getLifecycleService().isRunning()).isTrue();
        }
    }

    @Test
    void theFenceDoesNotExcuseAnotherEntryInTheSameMap() {
        old.getMap("dataprism.oversight").put("all", Boolean.TRUE);
        old.getMap("dataprism.oversight").put("tool" + NUL + "search", Boolean.TRUE);
        Throwable refused = catchThrowable(this::startNewMember);
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.UNSEALED_CLUSTER_STATE);
    }

    @Test
    void aSuppliedInstanceThatJoinedAClusterWithAnOldEntryIsRefusedAndLeftRunning() {
        old.getMap("dataprism.callerrate").put("caller" + NUL + "1", "7");
        // A hardened member, so that nothing but the old state can refuse it.
        Config config = PrivacyCluster.configure(ClusterMembership
                .tcpIp(cluster, List.of("127.0.0.1:" + oldPort, "127.0.0.1:" + newPort)).withPort(newPort)
                .withInterface("127.0.0.1").toConfig());
        HazelcastInstance supplied = Hazelcast.newHazelcastInstance(config);
        try {
            Throwable refused = catchThrowable(() -> PrivacyCluster.using(supplied, false, TestMembers.KEYS));
            assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
            assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.UNSEALED_CLUSTER_STATE);
            assertThat(supplied.getLifecycleService().isRunning()).isTrue();
        } finally {
            supplied.getLifecycleService().terminate();
        }
    }

    private HazelcastInstance rawMember(int port) {
        Config config = new Config().setClusterName(cluster);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        var join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).addMember("127.0.0.1:" + newPort);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        return Hazelcast.newHazelcastInstance(config);
    }

    @Test
    void aZeroSevenMemberThatJoinsLaterIsCountedAndTheRunningMemberKeepsServing() {
        old.getLifecycleService().terminate();
        HazelcastInstance late = null;
        HazelcastInstance trigger = null;
        try (PrivacyCluster running = startNewMember()) {
            assertThat(running.legacyEntries()).isZero();
            late = rawMember(FreePorts.consecutive(1));
            assertThat(await(30, () -> running.instance().getCluster().getMembers().size() == 2)).isTrue();
            late.getMap("dataprism.budget").put("scope-a" + NUL + "subject-a", 3);
            // The next membership change re-runs the check, off the listener thread.
            trigger = rawMember(FreePorts.consecutive(1));
            assertThat(await(30, () -> running.legacyEntries() > 0)).as("legacy entries counted").isTrue();
            // A running member is never shut down by what it finds: it keeps serving its own maps.
            assertThat(running.instance().getLifecycleService().isRunning()).isTrue();
            running.instance().<String, String>getMap(PrivacyCluster.CALLER_RATE_MAP).put("k", "v");
            assertThat(running.instance().<String, String>getMap(PrivacyCluster.CALLER_RATE_MAP).get("k"))
                    .isEqualTo("v");
        } finally {
            if (trigger != null) {
                trigger.getLifecycleService().terminate();
            }
            if (late != null) {
                late.getLifecycleService().terminate();
            }
        }
    }

    @Test
    void aStalledLegacyCheckIsInterruptedAtTheLimitAndNeverHoldsUpTheNextJoin() throws Exception {
        List<HazelcastInstance> joiners = new java.util.ArrayList<>();
        try (PrivacyCluster running = startNewMember()) {
            running.legacyCheckLimit = java.time.Duration.ofMillis(500);
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
            running.legacyProbe = instance -> {
                started.countDown();
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException atTheLimit) {
                    interrupted.countDown();
                }
                return java.util.Map.of();
            };
            joiners.add(rawMember(FreePorts.consecutive(1)));
            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(15, TimeUnit.SECONDS)).as("the stalled check was interrupted").isTrue();

            // A check that ignores its interrupt cannot be freed; the next join's check must still run.
            java.util.concurrent.CountDownLatch stuck = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            running.legacyProbe = instance -> {
                stuck.countDown();
                while (true) {
                    try {
                        release.await();
                        return java.util.Map.of();
                    } catch (InterruptedException ignored) {
                        // keeps waiting
                    }
                }
            };
            joiners.add(rawMember(FreePorts.consecutive(1)));
            assertThat(stuck.await(30, TimeUnit.SECONDS)).isTrue();
            running.legacyProbe = instance -> java.util.Map.of("dataprism.budget", 7);
            joiners.add(rawMember(FreePorts.consecutive(1)));
            assertThat(await(30, () -> running.legacyEntries() == 7)).as("the later check ran").isTrue();
            release.countDown();
        } finally {
            joiners.forEach(j -> j.getLifecycleService().terminate());
        }
    }

    /** A hardened single-member instance that someone else started, already in use. */
    private HazelcastInstance suppliedSolo() {
        int port = FreePorts.consecutive(1);
        Config config = PrivacyCluster.configure(ClusterMembership
                .tcpIp(TestMembers.uniqueName("supplied-solo"), List.of("127.0.0.1:" + port)).withPort(port)
                .withInterface("127.0.0.1").toConfig());
        return Hazelcast.newHazelcastInstance(config);
    }

    private void usingRefusesTheOldStateAndLeavesTheInstanceRunning(String map, Object key, Object value) {
        HazelcastInstance supplied = suppliedSolo();
        try {
            supplied.getMap(map).put(key, value);
            Throwable refused = catchThrowable(() -> PrivacyCluster.using(supplied, false, TestMembers.KEYS));
            assertThat(refused).as(map).isInstanceOf(PrivacyClusterRefusal.class);
            assertThat(((PrivacyClusterRefusal) refused).code()).as(map).isEqualTo(Code.UNSEALED_CLUSTER_STATE);
            assertThat(supplied.getLifecycleService().isRunning()).isTrue();
        } finally {
            supplied.getLifecycleService().terminate();
        }
    }

    @Test
    void aSuppliedSingleMemberInstanceHoldingAnOldPauseEntryIsRefusedAndLeftRunning() {
        usingRefusesTheOldStateAndLeavesTheInstanceRunning("dataprism.oversight", "tool" + NUL + "search",
                Boolean.TRUE);
    }

    @Test
    void aSuppliedSingleMemberInstanceHoldingAnOldBudgetEntryIsRefusedAndLeftRunning() {
        usingRefusesTheOldStateAndLeavesTheInstanceRunning("dataprism.budget", "scope-a" + NUL + "subject-a", 3);
    }

    @Test
    void aSuppliedSingleMemberInstanceHoldingOnlyTheFenceIsAccepted() {
        HazelcastInstance supplied = suppliedSolo();
        try {
            supplied.getMap("dataprism.oversight").put("all", Boolean.TRUE);
            assertThat(PrivacyCluster.using(supplied, false, TestMembers.KEYS).instance()).isSameAs(supplied);
        } finally {
            supplied.getLifecycleService().terminate();
        }
    }

    @Test
    void anEmptySuppliedSingleMemberInstanceIsAcceptedAndCreatesNoOldMapProxies() {
        HazelcastInstance supplied = suppliedSolo();
        try {
            supplied.getMap(PrivacyCluster.BUDGET_MAP).put("k", 1);
            assertThat(PrivacyCluster.using(supplied, false, TestMembers.KEYS).instance()).isSameAs(supplied);
            assertThat(supplied.getDistributedObjects().stream().map(o -> o.getName()))
                    .doesNotContainAnyElementsOf(PrivacyCluster.PRE_0_8_MAPS);
        } finally {
            supplied.getLifecycleService().terminate();
        }
    }

    private static boolean await(long seconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        return condition.getAsBoolean();
    }
}
