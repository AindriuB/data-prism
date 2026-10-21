package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;

import java.util.List;

/** The read budget and the per-caller request counts. */
final class CounterMaps {

    static final List<String> NAMES = List.of(PrivacyCluster.BUDGET_MAP, PrivacyCluster.CALLER_RATE_MAP);

    private CounterMaps() {
    }

    static void configure(Config config) {
        config.addMapConfig(PrivacyCluster.privacyMap(new MapConfig(PrivacyCluster.BUDGET_MAP)));
        MapConfig callerRate = PrivacyCluster.privacyMap(new MapConfig(PrivacyCluster.CALLER_RATE_MAP));
        // An evicted counter resets a caller's window; the 2-window TTL bounds size.
        callerRate.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(callerRate);
    }
}
