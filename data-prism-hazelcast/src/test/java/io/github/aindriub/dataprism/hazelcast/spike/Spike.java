package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Raw Hazelcast members for the spike tests. Nothing here uses Data Prism's own cluster classes. */
final class Spike {

    private Spike() {
    }

    static String name(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** A loopback tcp-ip config with Jet, phone-home, multicast and auto-detection off. */
    static Config config(String clusterName, int port, List<String> peers) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "jdk");
        // A refused or lost operation surfaces in seconds, not after the 2-minute defaults.
        config.setProperty("hazelcast.operation.call.timeout.millis", "5000");
        config.setProperty("hazelcast.operation.backup.timeout.millis", "5000");
        config.setProperty("hazelcast.client.invocation.timeout.seconds", "10");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        var join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(peers);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        return config;
    }

    /** {@code count} configs on consecutive ports that list each other. */
    static List<Config> configs(String clusterName, int count) {
        int first = SpikePorts.consecutive(count);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add("127.0.0.1:" + (first + i));
        }
        List<Config> configs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            configs.add(config(clusterName, first + i, all));
        }
        return configs;
    }

    static HazelcastInstance start(Config config) {
        return Hazelcast.newHazelcastInstance(config);
    }

    static void shutdown(HazelcastInstance... instances) {
        for (HazelcastInstance instance : instances) {
            if (instance != null) {
                try {
                    instance.getLifecycleService().terminate();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
    }

    /** "allowed" if {@code action} returns, otherwise the simple name of what it threw. */
    static String attempt(Runnable action) {
        try {
            action.run();
            return "allowed";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /** A client of the member on {@code port}, with the cluster name and nothing else. */
    static HazelcastInstance client(String clusterName, int port) {
        ClientConfig client = new ClientConfig();
        client.setClusterName(clusterName);
        client.getNetworkConfig().addAddress("127.0.0.1:" + port);
        return HazelcastClient.newHazelcastClient(client);
    }

    /** A key of the form {@code prefix-n} whose primary replica lives on {@code owner}. */
    static String keyOwnedBy(HazelcastInstance owner, String prefix) {
        String uuid = owner.getCluster().getLocalMember().getUuid().toString();
        // Partitions are assigned lazily, by the first operation that needs them.
        owner.getMap("partition-warmup").size();
        await(20, () -> owner.getPartitionService().getPartition("warm").getOwner() != null);
        for (int i = 0; i < 10_000; i++) {
            String key = prefix + "-" + i;
            if (owner.getPartitionService().getPartition(key).getOwner().getUuid().toString().equals(uuid)) {
                return key;
            }
        }
        throw new IllegalStateException("no key owned by the member");
    }

    /**
     * Two single-member clusters of one name that find each other and merge. The first lists both addresses and so
     * keeps looking for the second; merge checks run every 5 seconds. Returns {first, second} configs; start
     * the first, then the second.
     */
    static List<Config> splitPair(String clusterName) {
        int first = SpikePorts.consecutive(2);
        String a = "127.0.0.1:" + first;
        String b = "127.0.0.1:" + (first + 1);
        List<Config> configs = List.of(config(clusterName, first, List.of(a, b)),
                config(clusterName, first + 1, List.of(b)));
        configs.forEach(c -> {
            c.setProperty("hazelcast.merge.first.run.delay.seconds", "5");
            c.setProperty("hazelcast.merge.next.run.delay.seconds", "5");
        });
        return configs;
    }

    /**
     * Sends a raw Management Center protocol message through the client's own connection, the way the Java client
     * API never does. Returns "allowed" or the simple name of what the invocation failed with.
     */
    static String sendManagementMessage(HazelcastInstance client,
                                        com.hazelcast.client.impl.protocol.ClientMessage message) {
        var impl = ((com.hazelcast.client.impl.clientside.HazelcastClientProxy) client).client;
        try {
            new com.hazelcast.client.impl.spi.impl.ClientInvocation(impl, message, null).invoke()
                    .get(20, TimeUnit.SECONDS);
            return "allowed";
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause().getClass().getSimpleName();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    /** Polls {@code condition} every 50 ms for at most {@code seconds}; true if it held. */
    static boolean await(long seconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        return condition.getAsBoolean();
    }
}
