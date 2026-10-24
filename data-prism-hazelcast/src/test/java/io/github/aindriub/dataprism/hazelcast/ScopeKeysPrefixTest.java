package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The server-side prefix match picks exactly the keys {@code startsWith} would, wildcard characters included. */
class ScopeKeysPrefixTest {

    private PrivacyCluster cluster;

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private IMap<String, String> map() {
        Config config = new Config();
        config.setClusterName("dataprism-prefix-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, false);
        return cluster.instance().getMap("prefix-test");
    }

    @Test
    void anUnderscoreOrPercentInThePrefixMatchesOnlyItself() {
        IMap<String, String> entries = map();
        for (String key : new String[]{"a_b.1", "aXb.1", "a%b.1", "aXXb.1", "a_b", "z.a_b.1"}) {
            entries.put(key, "v");
        }

        assertThat(ScopeKeys.keysWithPrefix(entries, "a_b.")).containsExactly("a_b.1");
        assertThat(ScopeKeys.keysWithPrefix(entries, "a%b.")).containsExactly("a%b.1");
        assertThat(ScopeKeys.keysWithPrefix(entries, "none.")).isEmpty();
    }

    @Test
    void aDotInThePrefixIsNotAWildcardAndWhatFollowsThePrefixIsUnrestricted() {
        IMap<String, String> entries = map();
        for (String key : new String[]{"a_b.\n1", "a_bX1", "a_b.\u00001"}) {
            entries.put(key, "v");
        }

        assertThat(ScopeKeys.keysWithPrefix(entries, "a_b.")).containsExactlyInAnyOrder("a_b.\n1", "a_b.\u00001");
    }

    @Test
    void aPrefixWithABackslashIsRefused() {
        IMap<String, String> entries = map();

        assertThatThrownBy(() -> ScopeKeys.keysWithPrefix(entries, "a\\b."))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
