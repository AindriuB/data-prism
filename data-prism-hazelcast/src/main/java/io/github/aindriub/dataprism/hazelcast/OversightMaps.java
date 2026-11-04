package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;

import java.util.List;

/**
 * The pause document (one sealed entry, task 228), the 0.7 fence map that keeps its fence entry for a 0.7 member, and
 * the member cards. None of them is ever evicted or expired by configuration. {@link #NAMES} lists the maps with sealed
 * content and leaves the fence map out.
 */
final class OversightMaps {

    static final List<String> NAMES = List.of(PrivacyCluster.OVERSIGHT_MAP, PrivacyCluster.MEMBER_CARD_MAP);

    private OversightMaps() {
    }

    static void configure(Config config) {
        // An evicted pause document is a path silently reopened, or at best a refusal of everything until a pause
        // re-creates it. An evicted card or sentinel makes a verified member look unverified (cards carry their own
        // lease). The 0.7 map keeps the fence entry (all = TRUE) for a 0.7 member that joins; losing it reopens the
        // fence.
        for (String name : List.of(PrivacyCluster.OVERSIGHT_MAP, PrivacyCluster.OVERSIGHT_FENCE_MAP,
                PrivacyCluster.MEMBER_CARD_MAP)) {
            config.addMapConfig(neverEvicted(name));
        }
    }

    /** The privacy map config with eviction off, and no time to live or idle limit: the explicit zero is the point. */
    private static MapConfig neverEvicted(String name) {
        MapConfig map = PrivacyCluster.privacyMap(new MapConfig(name));
        map.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        map.setTimeToLiveSeconds(0).setMaxIdleSeconds(0);
        return map;
    }
}
