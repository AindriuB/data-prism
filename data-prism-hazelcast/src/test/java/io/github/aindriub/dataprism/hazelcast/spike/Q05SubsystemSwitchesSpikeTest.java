package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 5: on each way a member comes into being (Config built by {@code ClusterMembership.toConfig}, a supplied
 * Config, a supplied running instance), can Jet, user-code deployment, phone-home and SQL each be turned off, or be
 * read back as off, and by which switch?
 */
class Q05SubsystemSwitchesSpikeTest {

    private PrivacyCluster cluster;
    private HazelcastInstance supplied;

    @AfterEach
    void stop() {
        if (cluster != null) {
            cluster.close();
        }
        Spike.shutdown(supplied);
    }

    /** What a running member reports for each subsystem. */
    private static Map<String, String> reading(HazelcastInstance instance) {
        Map<String, String> reading = new LinkedHashMap<>();
        Config config = instance.getConfig();
        reading.put("jet", String.valueOf(config.getJetConfig().isEnabled()));
        reading.put("userCodeDeployment", String.valueOf(config.getUserCodeDeploymentConfig().isEnabled()));
        reading.put("userCodeNamespaces", String.valueOf(config.getNamespacesConfig().isEnabled()));
        // The effective value: config property, else -D system property, else the default (on).
        reading.put("phoneHome", String.valueOf(new com.hazelcast.spi.properties.HazelcastProperties(config)
                .getBoolean(com.hazelcast.spi.properties.ClusterProperty.PHONE_HOME_ENABLED)));
        // With the hazelcast-sql module absent from the classpath (as here) a query fails whatever the config says;
        // with Jet off the Jet check comes first.
        reading.put("sql", Spike.attempt(() -> instance.getSql().execute("SELECT 1").close()));
        return reading;
    }

    private static Config everythingOn(Config config) {
        config.getJetConfig().setEnabled(true);
        config.getUserCodeDeploymentConfig().setEnabled(true);
        config.setProperty("hazelcast.phone.home.enabled", "true");
        return config;
    }

    @Test
    void anUntouchedConfigHasJetAndUserCodeOffButPhoneHomeUnsetWhichMeansOn() {
        Config config = new Config();
        assertThat(config.getJetConfig().isEnabled()).isFalse();
        assertThat(config.getUserCodeDeploymentConfig().isEnabled()).isFalse();
        assertThat(config.getNamespacesConfig().isEnabled()).isFalse();
        assertThat(config.getProperty("hazelcast.phone.home.enabled")).isNull();
    }

    @Test
    void anUnsetPhoneHomePropertyReadsBackAsTheSystemPropertyAndAConfigPropertyWinsOverIt() {
        String key = "hazelcast.phone.home.enabled";
        String before = System.getProperty(key);
        try {
            // Set to false, never true, so that no test ever phones home.
            System.setProperty(key, "false");
            Config config = new Config();
            // Config.getProperty itself falls back to the system property when the config sets none.
            assertThat(config.getProperty(key)).isEqualTo("false");
            assertThat(new com.hazelcast.spi.properties.HazelcastProperties(config)
                    .getBoolean(com.hazelcast.spi.properties.ClusterProperty.PHONE_HOME_ENABLED)).isFalse();
            // A property in the config wins over the system property.
            config.setProperty(key, "true");
            assertThat(new com.hazelcast.spi.properties.HazelcastProperties(config)
                    .getBoolean(com.hazelcast.spi.properties.ClusterProperty.PHONE_HOME_ENABLED)).isTrue();
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    @Test
    void userCodeNamespacesCannotBeEnabledOnTheOpenSourceBuild() {
        Config config = Spike.config(Spike.name("q5n"), SpikePorts.consecutive(1), List.of());
        config.getNamespacesConfig().setEnabled(true);
        assertThat(Spike.attempt(() -> supplied = Spike.start(config))).isEqualTo("IllegalStateException");
    }

    @Test
    void theMembershipConfigReadsBackJetOffUserCodeOffPhoneHomeOffAndSqlRefused() {
        cluster = PrivacyCluster.embedded(ClusterMembership.none(Spike.name("q5a"))
                .withPort(SpikePorts.consecutive(1)), false);
        // Jet is switched off by JetConfig.setEnabled(false); SQL has no switch of its own and is refused because
        // it runs on the Jet engine; user-code deployment is off unless enabled; phone-home is a property.
        assertThat(reading(cluster.instance())).isEqualTo(Map.of("jet", "false", "userCodeDeployment", "false",
                "userCodeNamespaces", "false", "phoneHome", "false", "sql", "JetDisabledException"));
    }

    @Test
    void aSuppliedConfigKeepsJetAndUserCodeDeploymentOnButHasPhoneHomeForcedOff() {
        Config config = everythingOn(Spike.config(Spike.name("q5b"), SpikePorts.consecutive(1), List.of()));
        cluster = PrivacyCluster.embedded(config, false);
        assertThat(reading(cluster.instance())).isEqualTo(Map.of("jet", "true", "userCodeDeployment", "true",
                "userCodeNamespaces", "false", "phoneHome", "false", "sql", "HazelcastSqlException"));
    }

    @Test
    void aSuppliedInstanceKeepsEverythingItWasStartedWithIncludingPhoneHome() {
        supplied = Spike.start(everythingOn(Spike.config(Spike.name("q5c"), SpikePorts.consecutive(1), List.of())));
        cluster = PrivacyCluster.using(supplied, false);
        assertThat(reading(cluster.instance())).isEqualTo(Map.of("jet", "true", "userCodeDeployment", "true",
                "userCodeNamespaces", "false", "phoneHome", "true", "sql", "HazelcastSqlException"));
    }
}
