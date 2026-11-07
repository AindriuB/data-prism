package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A keyless member with a {@link HazelcastOversightState} on a fake nano clock and an hour-long tick, so the pause
 * document's lease can lapse without a sleep. It lives in this package because the clock seam is package-private
 * (task 286: the pause freshness must come from the oversight state, not the cluster's own status).
 */
public final class StalePauseFixture implements AutoCloseable {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final PrivacyCluster cluster;
    private final HazelcastOversightState state;

    public StalePauseFixture(String clusterName) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        this.cluster = PrivacyCluster.embedded(config, false);
        this.state = new HazelcastOversightState(cluster, ClusterLocks.DEFAULT_TIMEOUT, PrivacyMetrics.none(),
                new PauseDocument.Environment(nanos::get, Clock.systemUTC(), Duration.ofHours(1)));
    }

    public PrivacyCluster cluster() {
        return cluster;
    }

    public HazelcastOversightState state() {
        return state;
    }

    /** Moves the fake nano clock one second past the pause lease. */
    public void advancePastLease() {
        nanos.addAndGet(cluster.securitySettings().pauseLease().plusSeconds(1).toNanos());
    }

    @Override
    public void close() {
        state.close();
        cluster.close();
    }
}
