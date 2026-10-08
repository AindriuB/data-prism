package io.github.aindriub.dataprism.server.cluster;

import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.server.operator.OperatorHarness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * N real servers, one JVM, one embedded cluster over loopback TCP. Nothing about the cluster is
 * stubbed: every member is the production application with the operator surface on.
 */
final class ClusterMembers implements AutoCloseable {

    private static final long JOIN_TIMEOUT_MILLIS = 30_000;

    private static final long SAFE_TIMEOUT_MILLIS = 60_000;

    private final List<OperatorHarness> members = new ArrayList<>();

    static ClusterMembers start(Path tempDir, int count, String... extraArguments) throws Exception {
        ClusterMembers cluster = new ClusterMembers();
        try {
            cluster.launch(tempDir, count, extraArguments);
        } catch (Exception | Error failure) {
            cluster.close();
            throw failure;
        }
        return cluster;
    }

    private void launch(Path tempDir, int count, String[] extraArguments) throws Exception {
        String clusterName = "multi-member-test-" + System.nanoTime();
        int[] ports = freePorts(count);
        List<String> addresses = new ArrayList<>();
        for (int port : ports) {
            addresses.add("127.0.0.1:" + port);
        }
        for (int i = 0; i < count; i++) {
            List<String> cluster = new ArrayList<>(List.of(
                    "--dataprism.hazelcast.cluster-name=" + clusterName,
                    "--dataprism.hazelcast.join.mode=tcp-ip",
                    "--dataprism.hazelcast.member.port=" + ports[i],
                    "--dataprism.hazelcast.member.interface=127.0.0.1"));
            for (int m = 0; m < addresses.size(); m++) {
                cluster.add("--dataprism.hazelcast.join.members[" + m + "]=" + addresses.get(m));
            }
            members.add(OperatorHarness.startMember(tempDir, cluster, extraArguments));
        }
        awaitMembers(count);
        awaitClusterSafe();
    }

    OperatorHarness member(int index) {
        return members.get(index);
    }

    int size(int index) {
        return members.get(index).context.getBean(PrivacyCluster.class).instance().getCluster().getMembers().size();
    }

    /** Index of the member that owns the partition of {@code key}, as seen by member 0. */
    int ownerOf(String mapName, String key) {
        var instance = members.get(0).context.getBean(PrivacyCluster.class).instance();
        var owner = instance.getPartitionService().getPartition(key).getOwner();
        for (int i = 0; i < members.size(); i++) {
            var local = members.get(i).context.getBean(PrivacyCluster.class).instance().getCluster().getLocalMember();
            if (local.getUuid().equals(owner.getUuid())) {
                return i;
            }
        }
        throw new AssertionError("no member owns the partition of " + mapName + "/" + key);
    }

    /** Kills a member's Hazelcast instance without a graceful shutdown, then closes its harness. */
    void terminate(int index) {
        members.get(index).context.getBean(PrivacyCluster.class).instance().getLifecycleService().terminate();
        members.get(index).close();
    }

    /** Waits until every still-running member sees exactly {@code expected} members. */
    void awaitMembers(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + JOIN_TIMEOUT_MILLIS * 1_000_000;
        List<Integer> observed = new ArrayList<>();
        while (true) {
            observed.clear();
            boolean agreed = true;
            for (int i = 0; i < members.size(); i++) {
                if (!members.get(i).context.isActive()) {
                    continue;
                }
                int seen = size(i);
                observed.add(seen);
                agreed &= seen == expected;
            }
            if (agreed) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + expected + " cluster members within "
                        + JOIN_TIMEOUT_MILLIS / 1000 + "s; observed sizes per member: " + observed);
            }
            Thread.sleep(100);
        }
    }

    /** Waits until every running member reports the cluster safe (no partition lacks its backup). */
    void awaitClusterSafe() throws InterruptedException {
        long deadline = System.nanoTime() + SAFE_TIMEOUT_MILLIS * 1_000_000;
        List<Boolean> observed = new ArrayList<>();
        while (true) {
            observed.clear();
            boolean safe = true;
            for (OperatorHarness member : members) {
                if (!member.context.isActive()) {
                    continue;
                }
                boolean memberSafe = member.context.getBean(PrivacyCluster.class).instance()
                        .getPartitionService().isClusterSafe();
                observed.add(memberSafe);
                safe &= memberSafe;
            }
            if (safe) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("cluster not safe within " + SAFE_TIMEOUT_MILLIS / 1000
                        + "s; isClusterSafe per member: " + observed);
            }
            Thread.sleep(100);
        }
    }

    /** Distinct ports, each claimed by {@link OperatorHarness#freePort()} so no build hands one out twice. */
    private static int[] freePorts(int count) throws Exception {
        int[] ports = new int[count];
        for (int i = 0; i < count; i++) {
            ports[i] = OperatorHarness.freePort();
        }
        return ports;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int i = members.size() - 1; i >= 0; i--) {
            try {
                members.get(i).close();
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e instanceof RuntimeException r ? r : new IllegalStateException(e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
