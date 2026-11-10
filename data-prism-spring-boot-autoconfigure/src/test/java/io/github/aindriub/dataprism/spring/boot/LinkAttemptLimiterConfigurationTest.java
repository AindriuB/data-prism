package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.link.AttemptSubject;
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

    /** Counts as shared by every member; the delegate only supplies the behaviour. */
    @Configuration(proxyBeanMethods = false)
    static class ClusterWideLimiter {
        @Bean LinkAttemptLimiter sharedLimiter() {
            return new StubLimiter(true);
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
        factory.registerSingleton("sharedLimiter", new StubLimiter(true));
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
    void the_in_memory_limiter_is_built_from_the_configured_limit_and_window() {
        runner("single-node", "none", LINKS_ON).withPropertyValues("dataprism.links.invalid-attempt-limit=3",
                "dataprism.links.invalid-attempt-window=PT1M").run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(LinkAttemptLimiter.class)).isInstanceOf(InMemoryLinkAttemptLimiter.class);
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
