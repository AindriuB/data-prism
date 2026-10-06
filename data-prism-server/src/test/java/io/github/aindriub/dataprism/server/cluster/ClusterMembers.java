package io.github.aindriub.dataprism.server.cluster;

import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.server.operator.OperatorHarness;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * N real servers, one JVM, one embedded cluster over loopback TCP. Nothing about the cluster is
 * stubbed: every member is the production application with the operator surface on.
 */
final class ClusterMembers implements AutoCloseable {

    private static final long JOIN_TIMEOUT_MILLIS = 30_000;

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
    }

    OperatorHarness member(int index) {
        return members.get(index);
    }

    int size(int index) {
        return members.get(index).context.getBean(PrivacyCluster.class).instance().getCluster().getMembers().size();
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

    /** Distinct loopback ports, probed together so none repeats, with a retry on a failed probe. */
    private static int[] freePorts(int count) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<ServerSocket> probes = new ArrayList<>();
            try {
                int[] ports = new int[count];
                for (int i = 0; i < count; i++) {
                    ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                    probes.add(probe);
                    ports[i] = probe.getLocalPort();
                }
                return ports;
            } catch (IOException e) {
                last = e;
            } finally {
                for (ServerSocket probe : probes) {
                    try {
                        probe.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
        throw new IllegalStateException("no free loopback ports after 3 attempts", last);
    }

    @Override
    public void close() {
        for (int i = members.size() - 1; i >= 0; i--) {
            members.get(i).close();
        }
    }
}
