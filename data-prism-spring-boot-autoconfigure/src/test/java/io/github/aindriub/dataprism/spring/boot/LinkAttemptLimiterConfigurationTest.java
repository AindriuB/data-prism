package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.ClusterWideLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The attempt limiter and {@code MISSING_SHARED_LINK_LIMITER} (OD-08-2, fallback (c)): a sealed-token consumer is
 * active when links are enabled or a link key is configured, page links included. On a member that joins a cluster
 * through {@code tcp-ip} or {@code kubernetes} the count must be shared by every member, and no cluster-wide limiter
 * ships in 1.0, so startup is refused unless the application supplies one. {@code single-node} and
 * {@code join.mode: none} never are. With no consumer active there is no refusal anywhere.
 */
class LinkAttemptLimiterConfigurationTest {

    private static final String[] LINKS_ON = {"dataprism.links.key-id=lk1",
            "dataprism.links.keys.lk1.environment-variable=LINK_REF", "dataprism.links.enabled=true"};
    private static final String[] LINK_KEY_ONLY = {"dataprism.links.key-id=lk1",
            "dataprism.links.keys.lk1.environment-variable=LINK_REF"};
    private static final String[] NO_LINKS = {};

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    /** Declared as the marker, so it counts as shared by every member. */
    @Configuration(proxyBeanMethods = false)
    static class ClusterWideLimiter {
        @Bean ClusterWideLinkAttemptLimiter sharedLimiter() {
            return new StubClusterWide();
        }
    }

    /** Declared only as {@code LinkAttemptLimiter}, though its {@code clusterWide()} is {@code true}: refused. */
    @Configuration(proxyBeanMethods = false)
    static class ClaimedClusterWideLimiter {
        @Bean LinkAttemptLimiter claimedLimiter() {
            return new StubLimiter(true);
        }
    }

    /** A {@code FactoryBean} with no declared object type: the factory cannot read it without building it. */
    @Configuration(proxyBeanMethods = false)
    static class UntypedLimiterFactory {
        @Bean org.springframework.beans.factory.FactoryBean<?> untypedLimiter() {
            return new org.springframework.beans.factory.FactoryBean<Object>() {
                @Override public Object getObject() {
                    return new StubClusterWide();
                }

                @Override public Class<?> getObjectType() {
                    return null;
                }
            };
        }
    }

    /** Its factory method reads the bound properties; the recorder shows what it saw and that post-processors ran. */
    static final class Probe {
        static volatile String issuerSeen;
        static final List<String> BEFORE_INIT = new java.util.concurrent.CopyOnWriteArrayList<>();

        static void reset() {
            issuerSeen = null;
            BEFORE_INIT.clear();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InjectingClusterWideLimiter {
        @Bean ClusterWideLinkAttemptLimiter injectingLimiter(DataPrismProperties properties,
                                                             io.github.aindriub.dataprism.hazelcast.PrivacyCluster cluster) {
            Probe.issuerSeen = properties.getSecurity().getJwt().getIssuer();
            return new StubClusterWide();
        }

        @Bean static org.springframework.beans.factory.config.BeanPostProcessor limiterObserver() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof ClusterWideLinkAttemptLimiter) {
                        Probe.BEFORE_INIT.add(name);
                    }
                    return bean;
                }
            };
        }
    }

    static final class StubClusterWide implements ClusterWideLinkAttemptLimiter {
        @Override public LockState lockState(AttemptSubject subject) {
            return LockState.UNAVAILABLE;
        }

        @Override public Admission recordFailure(AttemptSubject subject) {
            return Admission.UNAVAILABLE;
        }
    }

    /** A clock the test moves. */
    @Configuration(proxyBeanMethods = false)
    static class MovableClock {
        static final java.util.concurrent.atomic.AtomicReference<java.time.Instant> NOW =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2030-01-01T00:00:00Z"));

        @Bean Clock dataPrismClock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() {
                    return java.time.ZoneOffset.UTC;
                }

                @Override public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override public java.time.Instant instant() {
                    return NOW.get();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalOnlyLimiter {
        @Bean LinkAttemptLimiter localLimiter() {
            return new StubLimiter(false);
        }
    }

    static final class StubLimiter implements LinkAttemptLimiter {
        private final boolean clusterWide;

        StubLimiter(boolean clusterWide) {
            this.clusterWide = clusterWide;
        }

        @Override public LockState lockState(AttemptSubject subject) {
            return LockState.UNAVAILABLE;
        }

        @Override public Admission recordFailure(AttemptSubject subject) {
            return Admission.UNAVAILABLE;
        }

        @Override public boolean clusterWide() {
            return clusterWide;
        }
    }

    private static WebApplicationContextRunner runner(String topology, String mode, String[] links,
                                                      Class<?>... extra) {
        Map<String, String> p = ClusterKeyConfigurationTest.valid(mode);
        if ("tcp-ip".equals(mode)) {
            int port = ClusterConfigurationTest.freePort();
            p.put("dataprism.hazelcast.join.members[0]", "127.0.0.1:" + port);
            p.put("dataprism.hazelcast.member.port", String.valueOf(port));
        } else if ("none".equals(mode)) {
            p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        }
        List<String> all = new ArrayList<>();
        p.forEach((k, v) -> all.add(k + "=" + v));
        if ("single-node".equals(topology)) {
            all.removeIf(s -> s.startsWith("dataprism.hazelcast.") || s.startsWith("dataprism.hazelcast"));
        }
        List<Class<?>> configurations = new ArrayList<>(List.of(ClusterKeyConfigurationTest.KeyIntegrations.class));
        configurations.addAll(List.of(extra));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(configurations.toArray(Class<?>[]::new))
                .withPropertyValues(OversightConfigurationTest.base(topology))
                .withPropertyValues(all.toArray(String[]::new))
                .withPropertyValues(links);
    }

    private static void refused(WebApplicationContextRunner runner) {
        runner.run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = LinksConfigurationTest.refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("MISSING_SHARED_LINK_LIMITER");
            assertThat(refusal.getMessage()).doesNotContain("LINK_REF").doesNotContain("lk1");
        });
    }

    // ---- tcp-ip, in a running context ----------------------------------------------------------

    @Test
    void tcp_ip_with_links_on_is_refused_without_a_cluster_wide_limiter() {
        refused(runner("embedded", "tcp-ip", LINKS_ON));
    }

    @Test
    void tcp_ip_with_only_a_link_key_is_refused_without_a_cluster_wide_limiter() {
        refused(runner("embedded", "tcp-ip", LINK_KEY_ONLY));
    }

    @Test
    void tcp_ip_is_refused_when_the_application_limiter_is_not_cluster_wide() {
        refused(runner("embedded", "tcp-ip", LINKS_ON, LocalOnlyLimiter.class));
    }

    @Test
    void tcp_ip_with_links_on_starts_with_a_cluster_wide_limiter_and_the_gate_uses_it() {
        runner("embedded", "tcp-ip", LINKS_ON, ClusterWideLimiter.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(LinkAttemptLimiter.class);
            assertThat(result.getBean(LinkAttemptLimiter.class).clusterWide()).isTrue();
            assertThat(result).hasSingleBean(LinkAttemptGate.class);
        });
    }

    @Test
    void tcp_ip_with_only_a_link_key_starts_with_a_cluster_wide_limiter() {
        runner("embedded", "tcp-ip", LINK_KEY_ONLY, ClusterWideLimiter.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void tcp_ip_refuses_a_limiter_declared_only_as_the_base_type_even_when_it_claims_to_be_cluster_wide() {
        refused(runner("embedded", "tcp-ip", LINKS_ON, ClaimedClusterWideLimiter.class));
    }

    @Test
    void tcp_ip_refuses_a_limiter_whose_type_cannot_be_read_without_building_it() {
        refused(runner("embedded", "tcp-ip", LINKS_ON, UntypedLimiterFactory.class));
    }

    @Test
    void the_refusal_tells_the_application_to_declare_the_marker_type() {
        runner("embedded", "tcp-ip", LINKS_ON, ClaimedClusterWideLimiter.class).run(result ->
                assertThat(LinksConfigurationTest.refusal(result.getStartupFailure()).getMessage())
                        .contains("ClusterWideLinkAttemptLimiter"));
    }

    @Test
    void a_cluster_wide_limiter_that_injects_the_properties_and_the_cluster_is_built_after_binding() {
        Probe.reset();
        runner("embedded", "tcp-ip", LINKS_ON, InjectingClusterWideLimiter.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(Probe.issuerSeen).isNotNull();
            assertThat(Probe.BEFORE_INIT).contains("injectingLimiter");
        });
    }

    @Test
    void the_preflight_builds_no_bean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("limiter", org.springframework.beans.factory.support.BeanDefinitionBuilder
                .rootBeanDefinition(StubClusterWide.class, () -> {
                    throw new AssertionError("the preflight built the limiter");
                }).getBeanDefinition());
        assertThatCode(() -> Preflights.dataPrismLinkLimiterPreflight(environment("tcp-ip", LINKS_ON))
                .postProcessBeanFactory(factory)).doesNotThrowAnyException();
    }

    // ---- an application-supplied PrivacyCluster counts as joining one ---------------------------------

    private static WebApplicationContextRunner suppliedCluster(Class<?>... extra) {
        List<Class<?>> configurations = new ArrayList<>(List.of(ClusterKeyConfigurationTest.KeyIntegrations.class,
                ClusterConfigurationTest.SuppliedCluster.class));
        configurations.addAll(List.of(extra));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(configurations.toArray(Class<?>[]::new))
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .withPropertyValues(LINKS_ON);
    }

    @Test
    void a_supplied_cluster_is_refused_without_a_limiter() {
        refused(suppliedCluster());
    }

    @Test
    void a_supplied_cluster_is_refused_with_a_limiter_declared_only_as_the_base_type() {
        refused(suppliedCluster(ClaimedClusterWideLimiter.class));
    }

    @Test
    void a_supplied_cluster_starts_with_a_cluster_wide_limiter() {
        suppliedCluster(ClusterWideLimiter.class).run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void the_supplied_cluster_refusal_message_names_the_case_and_no_value() {
        suppliedCluster().run(result -> assertThat(LinksConfigurationTest.refusal(result.getStartupFailure())
                .getMessage()).contains("supplies its own PrivacyCluster").doesNotContain("lk1")
                .doesNotContain("LINK_REF"));
    }

    @Test
    void the_framework_s_own_cluster_is_not_a_supplied_cluster() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("dataPrismPrivacyCluster",
                org.springframework.beans.factory.support.BeanDefinitionBuilder
                        .rootBeanDefinition(io.github.aindriub.dataprism.hazelcast.PrivacyCluster.class)
                        .setFactoryMethodOnBean("dataPrismPrivacyCluster", "clusterBackedState")
                        .getBeanDefinition());
        factory.registerBeanDefinition("clusterBackedState",
                org.springframework.beans.factory.support.BeanDefinitionBuilder
                        .rootBeanDefinition(ClusterBackedState.class).getBeanDefinition());
        assertThat(LinkWiring.suppliesCluster(factory)).isFalse();
    }

    // ---- kubernetes: refused in a running context; the started rows run the preflight itself ---------

    @ParameterizedTest
    @CsvSource({"links-on", "link-key-only"})
    void kubernetes_is_refused_without_a_cluster_wide_limiter(String links) {
        refused(runner("embedded", "kubernetes", "links-on".equals(links) ? LINKS_ON : LINK_KEY_ONLY));
    }

    @ParameterizedTest
    @CsvSource({"links-on", "link-key-only"})
    void kubernetes_starts_past_the_preflight_with_a_cluster_wide_limiter(String links) {
        MockEnvironment environment = environment("kubernetes", "links-on".equals(links) ? LINKS_ON : LINK_KEY_ONLY);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("sharedLimiter", new StubClusterWide());
        assertThatCode(() -> Preflights.dataPrismLinkLimiterPreflight(environment).postProcessBeanFactory(factory))
                .doesNotThrowAnyException();
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        assertThatCode(() -> Preflights.dataPrismLinkLimiterPreflight(environment).postProcessBeanFactory(none))
                .isInstanceOf(DataPrismConfigurationException.class)
                .extracting(e -> ((DataPrismConfigurationException) e).code()).isEqualTo("MISSING_SHARED_LINK_LIMITER");
    }

    @Test
    void the_in_memory_default_never_satisfies_the_preflight_on_a_joining_member() {
        MockEnvironment environment = environment("tcp-ip", LINKS_ON);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton(LinkWiring.DEFAULT_LIMITER,
                new InMemoryLinkAttemptLimiter(1, Duration.ofMinutes(1), Clock.systemUTC()));
        assertThatCode(() -> Preflights.dataPrismLinkLimiterPreflight(environment).postProcessBeanFactory(factory))
                .isInstanceOf(DataPrismConfigurationException.class);
    }

    // ---- single-node and join.mode none: never refused, in-memory --------------------------------------

    @ParameterizedTest
    @CsvSource({"single-node, none, links-on", "single-node, none, link-key-only", "embedded, none, links-on",
            "embedded, none, link-key-only"})
    void a_member_that_joins_no_one_starts_with_the_in_memory_limiter(String topology, String mode, String links) {
        runner(topology, mode, "links-on".equals(links) ? LINKS_ON : LINK_KEY_ONLY).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(LinkAttemptLimiter.class)).isInstanceOf(InMemoryLinkAttemptLimiter.class);
            assertThat(result.getBean(LinkAttemptLimiter.class).clusterWide()).isFalse();
            assertThat(result).hasSingleBean(LinkAttemptGate.class);
        });
    }

    @Test
    void the_in_memory_limiter_honours_the_configured_limit_and_window_with_a_fixed_clock() {
        MovableClock.NOW.set(java.time.Instant.parse("2030-01-01T00:00:00Z"));
        runner("single-node", "none", LINKS_ON, MovableClock.class).withPropertyValues(
                "dataprism.links.invalid-attempt-limit=3", "dataprism.links.invalid-attempt-window=PT1M")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    LinkAttemptLimiter limiter = result.getBean(LinkAttemptLimiter.class);
                    assertThat(limiter).isInstanceOf(InMemoryLinkAttemptLimiter.class);
                    AttemptSubject subject = new AttemptSubject("scope-a", "investigator-1");
                    for (int i = 0; i < 3; i++) {
                        assertThat(limiter.lockState(subject)).isEqualTo(LinkAttemptLimiter.LockState.UNLOCKED);
                        assertThat(limiter.recordFailure(subject)).isEqualTo(LinkAttemptLimiter.Admission.WITHIN_LIMIT);
                    }
                    assertThat(limiter.lockState(subject)).isEqualTo(LinkAttemptLimiter.LockState.LOCKED);
                    MovableClock.NOW.set(java.time.Instant.parse("2030-01-01T00:00:59Z"));
                    assertThat(limiter.lockState(subject)).isEqualTo(LinkAttemptLimiter.LockState.LOCKED);
                    MovableClock.NOW.set(java.time.Instant.parse("2030-01-01T00:01:01Z"));
                    assertThat(limiter.lockState(subject)).isEqualTo(LinkAttemptLimiter.LockState.UNLOCKED);
                });
    }

    @Test
    void an_application_limiter_replaces_the_default_on_single_node() {
        runner("single-node", "none", LINKS_ON, LocalOnlyLimiter.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(LinkAttemptLimiter.class);
            assertThat(result.getBean(LinkAttemptLimiter.class)).isInstanceOf(StubLimiter.class);
        });
    }

    // ---- no consumer: no limiter, no gate, no refusal ------------------------------------------------

    @ParameterizedTest
    @CsvSource({"single-node, none", "embedded, none", "embedded, tcp-ip"})
    void with_links_off_and_no_link_key_there_is_no_limiter_gate_or_refusal(String topology, String mode) {
        runner(topology, mode, NO_LINKS).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(LinkAttemptLimiter.class).doesNotHaveBean(LinkAttemptGate.class);
        });
    }

    @Test
    void with_links_off_and_no_link_key_kubernetes_is_not_refused() {
        MockEnvironment environment = environment("kubernetes", NO_LINKS);
        assertThatCode(() -> Preflights.dataPrismLinkLimiterPreflight(environment)
                .postProcessBeanFactory(new DefaultListableBeanFactory())).doesNotThrowAnyException();
    }

    private static MockEnvironment environment(String mode, String[] links) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dataprism.hazelcast.topology", "embedded")
                .withProperty("dataprism.hazelcast.join.mode", mode);
        for (String link : links) {
            int split = link.indexOf('=');
            environment.setProperty(link.substring(0, split), link.substring(split + 1));
        }
        return environment;
    }
}
