package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.util.Random;
import java.util.Set;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.net.InetAddress;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 132: an embedded topology says how its members find each other or it does not start.
 */
class ClusterConfigurationTest {

    private static final AtomicInteger NAMES = new AtomicInteger();

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    /** A cluster name no other test in this JVM uses. */
    static String uniqueName() {
        return "task132-" + System.nanoTime() + "-" + NAMES.incrementAndGet();
    }

    // Ports below the OS ephemeral range (no outgoing connection or port-0 bind takes one), probed on
    // loopback and wildcard, then claimed with a FileLock that every test JVM on this machine honours
    // and that is held until this JVM exits. A port is therefore never handed out twice, in this build
    // or in a concurrent one. At most three attempts, each on a failed probe or claim only.
    private static final Path PORT_CLAIMS = Path.of(System.getProperty("java.io.tmpdir"), "dataprism-test-ports");
    private static final List<FileChannel> CLAIMED = new ArrayList<>();
    private static final Set<Integer> CLAIMED_PORTS = new HashSet<>();
    private static final Random PORT_CHOICE = new Random();

    private static synchronized int claimPorts(int count) {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int base = 20000 + PORT_CHOICE.nextInt(12000);
            List<FileChannel> got = new ArrayList<>();
            try {
                Files.createDirectories(PORT_CLAIMS);
                for (int port = base; port < base + count; port++) {
                    if (CLAIMED_PORTS.contains(port)) {
                        // Never open a second channel on a file this JVM holds: closing it would drop our own lock.
                        throw new IOException("port " + port + " claimed in this JVM");
                    }
                    // One after the other: Linux refuses a wildcard bind while a loopback one is open. A
                    // foreign loopback listener fails the first probe; a foreign wildcard one (which on
                    // macOS the loopback probe cannot see) fails the second.
                    try (ServerSocket loopback = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                        // free on loopback
                    }
                    try (ServerSocket wildcard = new ServerSocket(port, 1)) {
                        // free on every address
                    }
                    FileChannel channel = FileChannel.open(PORT_CLAIMS.resolve("port-" + port),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    got.add(channel);
                    if (channel.tryLock() == null) {
                        throw new IOException("port " + port + " claimed by another build");
                    }
                }
                CLAIMED.addAll(got);
                for (int port = base; port < base + count; port++) {
                    CLAIMED_PORTS.add(port);
                }
                return base;
            } catch (IOException e) {
                last = e;
                for (FileChannel channel : got) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
        throw new IllegalStateException("no free loopback ports after 3 attempts", last);
    }

    static int freePort() {
        return claimPorts(1);
    }

    /** The settings an existing test adds to run an embedded topology as one member. */
    static String[] singleMember() {
        return new String[] {"dataprism.hazelcast.cluster-name=" + uniqueName(),
                "dataprism.hazelcast.join.mode=none", "dataprism.hazelcast.member.port=" + freePort()};
    }

    private static WebApplicationContextRunner runner(String topology, String... extra) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class)
                .withPropertyValues(OversightConfigurationTest.base(topology))
                .withPropertyValues(extra);
    }

    private static String[] with(String[] first, String... more) {
        List<String> all = new ArrayList<>(Arrays.asList(first));
        all.addAll(Arrays.asList(more));
        return all.toArray(String[]::new);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static void refuses(String code, String topology, String... properties) {
        runner(topology, properties).run(result -> assertRefused(result, code));
    }

    private static void assertRefused(AssertableWebApplicationContext result, String code) {
        assertThat(result).hasFailed();
        assertThat(rootMessage(result.getStartupFailure())).startsWith(code + ":");
    }

    private static void refusesEmbedded(String code, String... properties) {
        refuses(code, "embedded", properties);
    }

    private static final String NAME = "dataprism.hazelcast.cluster-name=prism-test-132";
    private static final String NONE = "dataprism.hazelcast.join.mode=none";

    // ---- embedded refusals ---------------------------------------------------------------------

    @Test void refuses_a_missing_cluster_name() {
        refusesEmbedded("MISSING_CLUSTER_NAME", NONE);
    }
    @Test void refuses_the_reserved_cluster_name() {
        refusesEmbedded("RESERVED_CLUSTER_NAME", "dataprism.hazelcast.cluster-name=dev", NONE);
    }
    @Test void refuses_the_reserved_cluster_name_in_any_case() {
        refusesEmbedded("RESERVED_CLUSTER_NAME", "dataprism.hazelcast.cluster-name=DEV", NONE);
    }
    @Test void refuses_a_missing_join_mode() {
        refusesEmbedded("MISSING_CLUSTER_JOIN", NAME);
    }
    @Test void refuses_an_unknown_join_mode() {
        refusesEmbedded("UNSUPPORTED_CLUSTER_JOIN", NAME, "dataprism.hazelcast.join.mode=multicast");
    }
    @Test void refuses_tcp_ip_without_members() {
        refusesEmbedded("INVALID_CLUSTER_MEMBERS", NAME, "dataprism.hazelcast.join.mode=tcp-ip");
    }
    @Test void refuses_kubernetes_without_a_namespace() {
        refusesEmbedded("INVALID_KUBERNETES_JOIN", NAME, "dataprism.hazelcast.join.mode=kubernetes",
                "dataprism.hazelcast.join.kubernetes.service-name=svc");
    }
    @Test void refuses_kubernetes_with_both_service_name_and_dns() {
        refusesEmbedded("INVALID_KUBERNETES_JOIN", NAME, "dataprism.hazelcast.join.mode=kubernetes",
                "dataprism.hazelcast.join.kubernetes.namespace=ns",
                "dataprism.hazelcast.join.kubernetes.service-name=svc",
                "dataprism.hazelcast.join.kubernetes.service-dns=svc.ns.svc.cluster.local");
    }
    @Test void refuses_kubernetes_with_neither_service_name_nor_dns() {
        refusesEmbedded("INVALID_KUBERNETES_JOIN", NAME, "dataprism.hazelcast.join.mode=kubernetes",
                "dataprism.hazelcast.join.kubernetes.namespace=ns");
    }
    @Test void refuses_a_member_port_equal_to_the_server_port() {
        refusesEmbedded("CLUSTER_PORT_SHARED", NAME, NONE, "server.port=5801",
                "dataprism.hazelcast.member.port=5801");
    }
    @Test void refuses_a_member_port_equal_to_the_management_port() {
        refusesEmbedded("CLUSTER_PORT_SHARED", NAME, NONE, "management.server.port=5802",
                "dataprism.hazelcast.member.port=5802");
    }
    @Test void refuses_a_member_port_equal_to_the_operator_port() {
        refusesEmbedded("CLUSTER_PORT_SHARED", NAME, NONE, "dataprism.operator.enabled=true",
                "dataprism.operator.port=5803", "dataprism.operator.required-audience=operator",
                "dataprism.operator.required-scope=s", "dataprism.hazelcast.member.port=5803");
    }
    @Test void passes_through_an_invalid_member_port() {
        refusesEmbedded("INVALID_CLUSTER_PORT", NAME, NONE, "dataprism.hazelcast.member.port=0");
    }
    @Test void passes_through_an_invalid_interface() {
        refusesEmbedded("INVALID_CLUSTER_INTERFACE", NAME, "dataprism.hazelcast.join.mode=tcp-ip",
                "dataprism.hazelcast.join.members[0]=127.0.0.1:5999", "dataprism.hazelcast.member.interface=not-an-ip");
    }
    @Test void refuses_an_interface_with_a_single_member() {
        refusesEmbedded("INVALID_CLUSTER_INTERFACE", NAME, NONE, "dataprism.hazelcast.member.interface=127.0.0.1");
    }

    // ---- single-node ---------------------------------------------------------------------------

    @Test void single_node_refuses_a_cluster_name() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", NAME);
    }
    @Test void single_node_refuses_a_join_mode() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", NONE);
    }
    @Test void single_node_refuses_join_members() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", "dataprism.hazelcast.join.members[0]=127.0.0.1");
    }
    @Test void single_node_refuses_a_member_port() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", "dataprism.hazelcast.member.port=5701");
    }
    @Test void single_node_refuses_a_member_interface() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", "dataprism.hazelcast.member.interface=127.0.0.1");
    }
    @Test void single_node_without_cluster_settings_starts() {
        runner("single-node").run(result -> assertThat(result).hasNotFailed());
    }

    // ---- TLS references ------------------------------------------------------------------------

    @Test void refuses_a_tls_key_reference_in_any_topology() {
        refuses("HAZELCAST_TLS_UNSUPPORTED", "single-node", "dataprism.hazelcast.tls-key-reference=k");
        refuses("HAZELCAST_TLS_UNSUPPORTED", "embedded", "dataprism.hazelcast.tls-key-reference=k");
    }
    @Test void refuses_a_tls_trust_reference_in_any_topology() {
        refuses("HAZELCAST_TLS_UNSUPPORTED", "single-node", "dataprism.hazelcast.tls-trust-reference=t");
        refuses("HAZELCAST_TLS_UNSUPPORTED", "embedded", with(singleMember(),
                "dataprism.hazelcast.tls-trust-reference=t"));
    }
    @Test void the_tls_refusal_names_network_isolation() {
        runner("single-node", "dataprism.hazelcast.tls-key-reference=k",
                "dataprism.hazelcast.tls-trust-reference=t").run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).contains("network isolation");
        });
    }

    // ---- application-supplied cluster ----------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class SuppliedCluster {
        @Bean(destroyMethod = "close") PrivacyCluster suppliedCluster() {
            Config config = new Config().setClusterName(uniqueName());
            config.getNetworkConfig().setPort(freePort()).setPortAutoIncrement(false);
            config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
            config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
            return PrivacyCluster.using(Hazelcast.newHazelcastInstance(config), false);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AutoDetectingCluster {
        @Bean PrivacyCluster suppliedCluster() {
            Config config = new Config().setClusterName(uniqueName());
            config.getNetworkConfig().setPort(freePort()).setPortAutoIncrement(false);
            config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
            config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(true);
            HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
            return PrivacyCluster.using(instance, false);
        }
    }

    @Test void an_application_cluster_needs_no_cluster_name_or_join() {
        runner("embedded").withUserConfiguration(SuppliedCluster.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBeansOfType(PrivacyCluster.class)).hasSize(1).containsKey("suppliedCluster");
            assertThat(result.getBean(ScopeBudget.class)).isNotNull();
        });
    }
    @Configuration(proxyBeanMethods = false)
    static class DefaultNamedCluster {
        @Bean(destroyMethod = "close") PrivacyCluster dataPrismPrivacyCluster() {
            return new SuppliedCluster().suppliedCluster();
        }
    }

    @Test void an_application_cluster_under_the_default_bean_name_needs_no_cluster_settings() {
        runner("embedded").withUserConfiguration(DefaultNamedCluster.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBeansOfType(PrivacyCluster.class)).hasSize(1);
        });
    }
    @Test void cluster_settings_beside_an_application_cluster_under_the_default_bean_name_are_refused() {
        runner("embedded", NAME, NONE).withUserConfiguration(DefaultNamedCluster.class)
                .run(result -> assertRefused(result, "CLUSTER_SETTINGS_IGNORED"));
    }
    @Test void cluster_settings_beside_an_application_cluster_are_refused() {
        runner("embedded", NAME, NONE).withUserConfiguration(SuppliedCluster.class)
                .run(result -> assertRefused(result, "CLUSTER_SETTINGS_IGNORED"));
    }
    @Test void an_application_cluster_with_auto_detection_is_refused() {
        runner("embedded").withUserConfiguration(AutoDetectingCluster.class)
                .run(result -> assertRefused(result, "UNSAFE_HAZELCAST_DISCOVERY"));
    }

    // ---- real clusters -------------------------------------------------------------------------

    @Test void two_contexts_from_properties_alone_form_a_cluster_and_a_none_context_stays_alone() {
        String name = uniqueName();
        int portA = freePort();
        int portB = freePort();
        String[] a = clusterProps(name, portA, portA, portB);
        String[] b = clusterProps(name, portB, portA, portB);
        runner("embedded", a).run(first -> runner("embedded", b).run(second -> {
            assertThat(first).hasNotFailed();
            assertThat(second).hasNotFailed();
            assertThat(first.getBean(PrivacyCluster.class).instance().getCluster().getMembers()).hasSize(2);
            assertThat(second.getBean(PrivacyCluster.class).instance().getCluster().getMembers()).hasSize(2);
            runner("embedded", "dataprism.hazelcast.cluster-name=" + name, NONE,
                    "dataprism.hazelcast.member.port=" + freePort()).run(third -> {
                assertThat(third).hasNotFailed();
                assertThat(third.getBean(PrivacyCluster.class).instance().getCluster().getMembers()).hasSize(1);
            });
        }));
    }

    private static String[] clusterProps(String name, int port, int portA, int portB) {
        return new String[] {"dataprism.hazelcast.cluster-name=" + name,
                "dataprism.hazelcast.join.mode=tcp-ip",
                "dataprism.hazelcast.join.members[0]=127.0.0.1:" + portA,
                "dataprism.hazelcast.join.members[1]=127.0.0.1:" + portB,
                "dataprism.hazelcast.member.port=" + port,
                "dataprism.hazelcast.member.interface=127.0.0.1"};
    }

    // ---- bean graph ----------------------------------------------------------------------------

    @Test void the_cluster_budget_depends_on_the_cluster_so_it_is_destroyed_first() {
        runner("embedded", singleMember()).run(result -> {
            assertThat(result).hasNotFailed();
            String[] budgets = result.getBeanFactory().getBeanNamesForType(ScopeBudget.class);
            assertThat(budgets).hasSize(1);
            assertThat(result.getBeanFactory().getDependenciesForBean(budgets[0]))
                    .contains("dataPrismPrivacyCluster");
        });
    }

    // ---- property names bind from the environment, as the examples write them ------------------

    @Test void the_environment_variables_reach_the_built_cluster_configuration() {
        int port = freePort();
        String name = uniqueName();
        runner("embedded").withInitializer(context -> TestPropertyValues.of(
                "DATAPRISM_HAZELCAST_CLUSTERNAME=" + name,
                "DATAPRISM_HAZELCAST_JOIN_MODE=tcp-ip",
                "DATAPRISM_HAZELCAST_JOIN_MEMBERS=127.0.0.1:" + port + ",127.0.0.1:" + (port + 1),
                "DATAPRISM_HAZELCAST_MEMBER_PORT=" + port,
                "DATAPRISM_HAZELCAST_MEMBER_INTERFACE=127.0.0.1")
                .applyTo(context.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT)).run(result -> {
            assertThat(result).hasNotFailed();
            Config config = result.getBean(PrivacyCluster.class).instance().getConfig();
            assertThat(config.getClusterName()).isEqualTo(name);
            assertThat(config.getNetworkConfig().getPort()).isEqualTo(port);
            assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().getMembers())
                    .containsExactly("127.0.0.1:" + port, "127.0.0.1:" + (port + 1));
            assertThat(config.getNetworkConfig().getInterfaces().getInterfaces()).containsExactly("127.0.0.1");
        });
    }

    @Test void the_cluster_name_is_passed_stripped_as_validated() {
        String name = uniqueName();
        runner("embedded", "dataprism.hazelcast.cluster-name=  " + name + "  ", NONE,
                "dataprism.hazelcast.member.port=" + freePort()).run(result -> {
            assertThat(result).hasNotFailed();
            String effective = result.getBean(PrivacyCluster.class).instance().getConfig().getClusterName();
            assertThat(effective).startsWith(name + "-solo-").isEqualTo(effective.strip());
        });
    }

    @Test void the_example_environment_variables_bind() {
        StandardEnvironment env = new StandardEnvironment();
        TestPropertyValues.of(
                "DATAPRISM_HAZELCAST_CLUSTERNAME=prism-env",
                "DATAPRISM_HAZELCAST_JOIN_MODE=kubernetes",
                "DATAPRISM_HAZELCAST_JOIN_MEMBERS=10.0.0.1,10.0.0.2:5702",
                "DATAPRISM_HAZELCAST_MEMBER_PORT=5712",
                "DATAPRISM_HAZELCAST_MEMBER_INTERFACE=10.0.*.*",
                "DATAPRISM_HAZELCAST_JOIN_KUBERNETES_NAMESPACE=ns",
                "DATAPRISM_HAZELCAST_JOIN_KUBERNETES_SERVICEDNS=svc.ns.svc.cluster.local",
                "DATAPRISM_HAZELCAST_JOIN_KUBERNETES_SERVICENAME=svc")
                .applyTo(env, TestPropertyValues.Type.SYSTEM_ENVIRONMENT);
        DataPrismProperties.Hazelcast h = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("dataprism.hazelcast", DataPrismProperties.Hazelcast.class).get();
        assertThat(h.getClusterName()).isEqualTo("prism-env");
        assertThat(h.getJoin().getMode()).isEqualTo("kubernetes");
        assertThat(h.getJoin().getMembers()).containsExactly("10.0.0.1", "10.0.0.2:5702");
        assertThat(h.getMember().getPort()).isEqualTo(5712);
        assertThat(h.getMember().getInterface()).isEqualTo("10.0.*.*");
        assertThat(h.getJoin().getKubernetes().getNamespace()).isEqualTo("ns");
        assertThat(h.getJoin().getKubernetes().getServiceDns()).isEqualTo("svc.ns.svc.cluster.local");
        assertThat(h.getJoin().getKubernetes().getServiceName()).isEqualTo("svc");
    }

    // ---- no values in messages -----------------------------------------------------------------

    @Test void refusals_name_the_property_never_its_value() {
        runner("embedded", NAME, "dataprism.hazelcast.join.mode=kubernetes",
                "dataprism.hazelcast.join.kubernetes.namespace=secret-ns",
                "dataprism.hazelcast.join.kubernetes.service-name=a",
                "dataprism.hazelcast.join.kubernetes.service-dns=b").run(result -> {
            assertNoValue(result, "INVALID_KUBERNETES_JOIN");
        });
        runner("embedded", NAME, "dataprism.hazelcast.join.mode=tcp-ip",
                "dataprism.hazelcast.join.members[0]=198.51.100.7:99999",
                "dataprism.hazelcast.join.kubernetes.namespace=secret-ns").run(result -> {
            assertThat(result).hasFailed();
            for (Throwable t = result.getStartupFailure(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain("198.51.100.7").doesNotContain("secret-ns");
            }
        });
    }

    private static void assertNoValue(AssertableWebApplicationContext result, String code) {
        assertRefused(result, code);
        for (Throwable t = result.getStartupFailure(); t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain("198.51.100.7").doesNotContain("secret-ns");
        }
    }
}
