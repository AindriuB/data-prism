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
