package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;

import java.util.List;

/** The pause state and the member cards. */
final class OversightMaps {

    static final List<String> NAMES = List.of(PrivacyCluster.OVERSIGHT_MAP, PrivacyCluster.MEMBER_CARD_MAP);

    private OversightMaps() {
    }

    static void configure(Config config) {
        MapConfig oversight = PrivacyCluster.privacyMap(new MapConfig(PrivacyCluster.OVERSIGHT_MAP));
        // An evicted pause flag is a path silently reopened.
        oversight.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(oversight);
        MapConfig cards = PrivacyCluster.privacyMap(new MapConfig(PrivacyCluster.MEMBER_CARD_MAP));
        // An evicted card or sentinel makes a verified member look unverified. Cards carry their own lease.
        cards.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(cards);
    }
}
