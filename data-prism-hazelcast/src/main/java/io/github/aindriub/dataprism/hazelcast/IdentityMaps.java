package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;

import java.util.List;

/**
 * The identity cache and the re-identification index. Both use the common map settings and nothing more: an
 * evicted identity entry costs a recomputed value, and an evicted re-identification entry is the documented
 * durability limit of the embedded cluster.
 */
final class IdentityMaps {

    static final List<String> NAMES = List.of(PrivacyCluster.IDENTITY_MAP, PrivacyCluster.REIDENTIFICATION_MAP);

    private IdentityMaps() {
    }

    static void configure(Config config) {
        NAMES.forEach(name -> config.addMapConfig(PrivacyCluster.privacyMap(new MapConfig(name))));
    }
}
