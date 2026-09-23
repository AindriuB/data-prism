package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.hazelcast.HazelcastScopeBudget;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dataprism.hazelcast.topology} is a choice with two real, distinct
 * consequences, not a label read by nothing:
 *
 * <ul>
 *   <li>{@code embedded} must produce a {@link ScopeBudget} that is actually
 *       shared across the cluster ({@link HazelcastScopeBudget} over a
 *       {@link PrivacyCluster}), never the per-process {@link InMemoryScopeBudget}
 *       silently standing in for it;
 *   <li>{@code embedded} with the optional {@code data-prism-hazelcast}
 *       dependency absent must refuse startup with a stable code, never fall
 *       back to an unshared budget that looks the same as the shared one;
 *   <li>{@code single-node} must produce the per-process budget, honestly.
 * </ul>
 */
class SharedReadBudgetTest {

    @AfterEach
    void shutDownEveryClusterMemberThisTestStarted() {
        // ClusterScopeBudgetConfiguration builds a PrivacyCluster from a plain
        // new Config(), which this test never gets a handle to directly — the
        // bean is a ScopeBudget, not a PrivacyCluster. Hazelcast.shutdownAll()
        // is the only way to stop the embedded member(s) a test below started,
        // rather than leaking them into every later test in this JVM.
        Hazelcast.shutdownAll();
    }

    @Test
    void embedded_topology_produces_a_cluster_backed_budget() {
        runner("embedded").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(ScopeBudget.class)).isInstanceOf(HazelcastScopeBudget.class);
        });
    }

    @Test
    void embedded_topology_honours_reidentification_enabled() {
        runner("embedded", "dataprism.hazelcast.reidentification-enabled=true",
                "dataprism.hazelcast.reidentification-controls-reference=REVIEWED_REIDENTIFICATION_CONTROLS")
                .run(result -> {
            assertThat(result).hasNotFailed();
            ScopeBudget budget = result.getBean(ScopeBudget.class);
            PrivacyCluster cluster = (PrivacyCluster) ReflectionTestUtils.getField(budget, "cluster");
            assertThat(cluster.reidentificationEnabled()).isTrue();
        });
    }

    @Test
    void single_node_topology_produces_the_per_process_budget() {
        runner("single-node").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(ScopeBudget.class)).isInstanceOf(InMemoryScopeBudget.class);
        });
    }

    /**
     * The one most at risk of a cannot-fail assertion: if the classloader below
     * did not really remove Hazelcast from view, this context would boot with a
     * {@link HazelcastScopeBudget} and the assertion on the refusal code would
     * simply never run against the path it claims to. Proven by mutation instead
     * of trusted on sight — see this task's close-out for both observations.
     */
    @Test
    void embedded_topology_without_hazelcast_on_the_classpath_refuses_startup() {
        runner("embedded")
                .withClassLoader(new FilteredClassLoader("com.hazelcast"))
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).contains("MISSING_SHARED_BUDGET");
                });
    }

    /**
     * Every other test in this class configures {@code dataprism.audit.sink=approved-sink}
     * (see {@link #runner}) alongside an {@code AuditSink} bean, so none of them ever
     * drives {@link DataPrismContractValidator} far enough to notice whether an absent
     * bean still refuses -- the shared-budget outcome they assert is unrelated. Task 73:
     * pin that explicitly, with the bean withheld, rather than leave the gap.
     */
    @Test
    void approvedAuditSinkWithNoAuditSinkBeanRefusesAtTheContractValidator() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrationsWithoutAudit.class)
                .withPropertyValues(
                        "dataprism.security.jwt.issuer=https://issuer.example",
                        "dataprism.security.jwt.audience=mcp",
                        "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                        "dataprism.security.caller-claims.principal=sub",
                        "dataprism.security.caller-claims.roles=roles",
                        "dataprism.security.caller-claims.investigation=case_id",
                        "dataprism.security-policy.purposes[0]=investigation",
                        "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                        "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                        "dataprism.privacy.hmac-key.key-id=v1",
                        "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                        "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                        "dataprism.metrics.sink=micrometer",
                        "dataprism.sources.customer.base-url=https://customer.example",
                        "dataprism.sources.customer.timeout=2s",
                        "dataprism.hazelcast.topology=single-node");

        runner.run(result -> {
            assertThat(result).hasFailed();
            String message = rootMessage(result.getStartupFailure());
            assertThat(message).contains("AUDIT_SINK_BEAN_REQUIRED");
            assertThat(message).contains("dataprism.audit.sink=approved-sink");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedHttpIntegrationsWithoutAudit {
        @Bean DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(DataRequest request) { throw new UnsupportedOperationException(); }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() {
            return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes();
        }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }

    /**
     * Migrated from {@code ApplicationContextRunner} to {@link
     * WebApplicationContextRunner} (task 39): the new {@code
     * dataPrismMcpTransportPreflight} refuses a non-web context at the default
     * {@code dataprism.transport.mode=HTTP}. Every assertion in this class is
     * unchanged; only the runner type and the fixture — {@code ReviewedIntegrations}
     * swapped for {@code ReviewedHttpIntegrations}, which adds the caller-context
     * extractor the HTTP transport requires — changed.
     */
    private static WebApplicationContextRunner runner(String topology, String... extra) {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class)
                .withPropertyValues(
                        "dataprism.security.jwt.issuer=https://issuer.example",
                        "dataprism.security.jwt.audience=mcp",
                        "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                        "dataprism.security.caller-claims.principal=sub",
                        "dataprism.security.caller-claims.roles=roles",
                        "dataprism.security.caller-claims.investigation=case_id",
                        "dataprism.security-policy.purposes[0]=investigation",
                        "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                        "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                        "dataprism.privacy.hmac-key.key-id=v1",
                        "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                        "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                        "dataprism.metrics.sink=micrometer",
                        "dataprism.sources.customer.base-url=https://customer.example",
                        "dataprism.sources.customer.timeout=2s",
                        "dataprism.hazelcast.topology=" + topology);
        return extra.length == 0 ? runner : runner.withPropertyValues(extra);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }
}
