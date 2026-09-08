package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

/**
 * The embedded member and the maps it holds.
 *
 * <p>Embedded rather than a separate cluster. The usual objection to embedding is
 * that members in an autoscaled deployment rebalance partitions on every scale
 * event, which sounds ruinous for a map that decides what a subject is called.
 * It is not, because of a property designed in from the start: a synthetic value
 * is a pure function of scope, subject, namespace and key, so losing a partition
 * costs a recomputed HMAC and never a different answer. Rebalancing produces
 * cache misses, not renamed people.
 *
 * <p>What that argument does <em>not</em> cover is the re-identification index,
 * which is a store rather than a cache: nothing can recompute a subject id from a
 * pseudonym. An embedded cluster scaled to zero loses it. It is therefore off by
 * default, and a deployment that turns it on has to decide about persistence and
 * accept that its durability is the cluster's durability.
 *
 * <p>Three settings here are security rather than tuning. No {@code MapStore}, so
 * nothing is written through to a database nobody audited. No persistence, so the
 * maps do not survive on disk by accident. A TTL on every entry, so a scope that
 * is never explicitly ended still expires. The transport security the
 * specification requires — TLS, member authentication, network isolation — is
 * deployment configuration and is not, and cannot be, defaulted here.
 */
public final class PrivacyCluster implements AutoCloseable {

    /** Synthetic values, recomputable. Losing this map costs CPU and nothing else. */
    public static final String IDENTITY_MAP = "dataprism.identity";

    /** Pseudonym to subject. Not recomputable, and the most sensitive object here. */
    public static final String REIDENTIFICATION_MAP = "dataprism.reidentification";

    /** Per-subject read counts, shared so the budget means what it says. */
    public static final String BUDGET_MAP = "dataprism.budget";

    private final HazelcastInstance instance;
    private final boolean reidentificationEnabled;

    private PrivacyCluster(HazelcastInstance instance, boolean reidentificationEnabled) {
        this.instance = instance;
        this.reidentificationEnabled = reidentificationEnabled;
    }

    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled) {
        return new PrivacyCluster(Hazelcast.newHazelcastInstance(configure(config)),
                reidentificationEnabled);
    }

    /** Wraps a member someone else started, for a host that manages its own instance. */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled) {
        return new PrivacyCluster(instance, reidentificationEnabled);
    }

    /**
     * Applies the settings that are not the caller's to choose. Everything else on
     * the supplied config — cluster name, discovery, TLS, member authentication —
     * is left alone, because those are deployment decisions and guessing at them
     * is how a cluster ends up reachable from somewhere it should not be.
     */
    static Config configure(Config config) {
        config.addMapConfig(privacyMap(new MapConfig(IDENTITY_MAP)));
        config.addMapConfig(privacyMap(new MapConfig(REIDENTIFICATION_MAP)));
        config.addMapConfig(privacyMap(new MapConfig(BUDGET_MAP)));
        return config;
    }

    private static MapConfig privacyMap(MapConfig map) {
        map.getMapStoreConfig().setEnabled(false);
        map.setBackupCount(1);
        map.setStatisticsEnabled(true);
        // Scope-prefixed keys, so ending a scope is a prefix scan rather than a
        // walk of every entry in the cluster.
        map.addIndexConfig(new IndexConfig(IndexType.HASH, "__key"));
        map.setEvictionConfig(new EvictionConfig()
                .setEvictionPolicy(EvictionPolicy.LRU)
                .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                .setSize(100_000));
        return map;
    }

    public HazelcastInstance instance() {
        return instance;
    }

    public boolean reidentificationEnabled() {
        return reidentificationEnabled;
    }

    @Override
    public void close() {
        instance.shutdown();
    }
}
