package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.FileAuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataPrismAutoConfigurationTest {
    /**
     * Migrated from {@link ApplicationContextRunner} to {@link
     * WebApplicationContextRunner} (task 39): the new {@code
     * dataPrismMcpTransportPreflight} refuses a non-web context at the default
     * {@code dataprism.transport.mode=HTTP}, which every test below that expects
     * {@code hasNotFailed()} or a refusal unrelated to transport availability
     * depends on this context actually being a servlet web application. Every
     * assertion these tests made before the migration is unchanged; only the
     * runner type and the addition of {@link ReviewedHttpIntegrations#callerExtractor()}
     * (already required by {@code http_transport_refuses_a_missing_caller_context_extractor}
     * elsewhere in this class) changed.
     */
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedHttpIntegrations.class)
            .withPropertyValues(valid());

    @Test void boots_a_minimal_reviewed_context() {
        context.run(result -> assertThat(result).hasNotFailed());
    }
    /**
     * This is the case task 39 closes: a non-web application at the default
     * {@code dataprism.transport.mode=HTTP} previously started successfully with
     * no MCP transport registered at all. See the commit body for the mutation
     * that reproduces the pre-fix behaviour this test now refuses instead of
     * exhibiting: with {@code dataPrismMcpTransportPreflight} removed, this exact
     * context starts, {@code hasNotFailed()}, and registers zero {@link
     * McpSyncServer} beans and zero MCP {@code ServletRegistrationBean}s.
     */
    @Test void non_web_application_refuses_startup_with_no_usable_mcp_transport() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedIntegrations.class).withPropertyValues(valid())
                .run(result -> {
                    assertThat(result).hasFailed();
                    Throwable failure = rootCause(result.getStartupFailure());
                    assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
                    assertThat(failure.getMessage()).startsWith("MCP_TRANSPORT_UNAVAILABLE:");
                    assertThat(failure.getMessage()).contains("no MCP transport is registered");
                });
    }
    /**
     * Proves the refusal happens before any DataPrism singleton is constructed
     * (task 39, acceptance item 3): {@link ConstructionMarker} records its own
     * construction, and never does so on the refusing, non-web path.
     */
    @Test void refusal_happens_before_any_dataprism_singleton_is_constructed() {
        ConstructionMarker.constructed = false;
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedIntegrationsWithConstructionMarker.class).withPropertyValues(valid())
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).contains("MCP_TRANSPORT_UNAVAILABLE");
                });
        assertThat(ConstructionMarker.constructed)
                .as("no DataPrism singleton should be constructed once the transport preflight refuses")
                .isFalse();
    }
    @Test void configured_reference_is_used_for_runtime_key_resolution() {
        context.run(result -> assertThat(new String(result.getBean(SecretKeyProvider.class).secret("v1"))).isEqualTo("DATAPRISM_HMAC_KEY_REF:v1:resolved-key-material"));
    }
    @Test void creates_the_configured_http_endpoint_and_server_lifecycle() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrations.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.transport.http.path=/protected-mcp").run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(DataPrismMcpServer.HttpTransport.class)
                            .hasSingleBean(McpSyncServer.class);
                    ServletRegistrationBean<?> servlet = result.getBean("dataPrismMcpServlet",
                            ServletRegistrationBean.class);
                    assertThat(servlet.getUrlMappings()).containsExactly("/protected-mcp");
                    assertThat(servlet.isAsyncSupported()).isTrue();
                });
    }
    /**
     * Before this refusal existed, a starter-shaped context configured this way
     * started successfully and registered no {@link McpSyncServer} and no MCP
     * {@code ServletRegistrationBean} at all — a running server with no transport,
     * which is exactly the fail-open failure mode this closes. See the commit body
     * for the mutation that reproduced that behaviour.
     */
    @Test void stdio_transport_refuses_startup_even_with_fixture_development() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrations.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.transport.mode=stdio",
                        "dataprism.transport.fixture-development=true")
                .run(result -> {
                    assertThat(result).hasFailed();
                    Throwable failure = rootCause(result.getStartupFailure());
                    assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
                    assertThat(failure.getMessage()).startsWith("STDIO_TRANSPORT_UNSUPPORTED:").contains("stdio");
                });
    }
    @Test void stdio_transport_refuses_startup_without_fixture_development() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrations.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.transport.mode=stdio",
                        "dataprism.transport.fixture-development=false")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).contains("STDIO_DEVELOPMENT_ONLY");
                });
    }
    @Test void http_transport_refuses_a_missing_caller_context_extractor() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedIntegrations.class).withPropertyValues(valid())
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure()))
                            .contains("MISSING_CALLER_CONTEXT_EXTRACTOR");
                });
    }
    @Test void application_secret_provider_cannot_override_the_configured_reference() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(IntegrationsWithCompetingSecretProvider.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(new String(result.getBean(SecretKeyProvider.class).secret("v1"))).isEqualTo("DATAPRISM_HMAC_KEY_REF:v1:resolved-key-material");
                });
    }
    @Test void refuses_an_unsafe_source_url() { fails("INVALID_SOURCE_URL", "dataprism.sources.customer.base-url=http://api.example"); }
    @Test void refuses_a_missing_key_reference() { fails("MISSING_HMAC_KEY_REFERENCE", "dataprism.privacy.hmac-key.environment-variable="); }
    @Test void refuses_an_unknown_capability() { fails("UNKNOWN_CAPABILITY", "dataprism.security-policy.roles.investigator[0]=ADMIN"); }
    @Test void refuses_an_empty_purpose_list() { fails("EMPTY_PURPOSE_LIST", "dataprism.security-policy.purposes="); }
    @Test void refuses_production_stdio() { fails("STDIO_DEVELOPMENT_ONLY", "dataprism.transport.mode=stdio"); }
    @Test void refuses_an_unknown_profile() { fails("UNKNOWN_PRIVACY_PROFILE", "dataprism.privacy.profile=UNREVIEWED"); }
    @Test void refuses_a_reserved_caller_claim() { fails("RESERVED_CALLER_CLAIM", "dataprism.security.caller-claims.principal=caseId"); }
    @Test void refuses_a_partial_mtls_reference() { fails("INVALID_SOURCE_MTLS", "dataprism.sources.customer.mtls.key-reference=key-ref"); }
    @Test void refuses_an_unknown_audit_sink() { fails("UNKNOWN_AUDIT_SINK", "dataprism.audit.sink=stdout"); }
    @Test void refuses_a_missing_cluster_topology() { fails("MISSING_CLUSTER_TOPOLOGY", "dataprism.hazelcast.topology="); }
    @Test void refuses_an_unknown_topology() { fails("UNSUPPORTED_HAZELCAST_TOPOLOGY", "dataprism.hazelcast.topology=client-server"); }
    @Test void refuses_weak_key_material() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(WeakKeyIntegrations.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("HMAC_KEY_WEAK");
                });
    }
    @Test void refuses_an_unresolved_configured_key_reference() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(UnresolvedKeyIntegrations.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("HMAC_KEY_UNRESOLVED");
                });
    }
    @Test void refuses_an_unresolved_identity_resolver() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(IntegrationsWithoutIdentity.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("MISSING_IDENTITY_RESOLVER");
                });
    }
    @Test void refuses_an_unresolved_source_adapter() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(IntegrationsWithoutAdapter.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("UNRESOLVED_SOURCE_ADAPTER");
                });
    }
    /**
     * Task 67: {@code dataprism.audit.sink=hash-chained} resolves to task 64's
     * {@link FileAuditSink}, bound to the configured {@code
     * dataprism.audit.file-path}, rather than passing config validation with no
     * bean behind it. Uses {@link ReviewedHttpIntegrationsWithoutAudit} — no
     * application-supplied {@link AuditSink} — so the bean under test is the
     * autoconfiguration's own, not one {@code @ConditionalOnMissingBean} would
     * have suppressed.
     */
    @Test void hash_chained_sink_produces_a_file_audit_sink_bound_to_the_configured_path(@TempDir Path tempDir) {
        Path file = tempDir.resolve("audit.log");
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrationsWithoutAudit.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained", "dataprism.audit.file-path=" + file)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(AuditSink.class);
                    assertThat(result.getBean(AuditSink.class)).isInstanceOf(FileAuditSink.class);
                    assertThat(result.getBeansOfType(Slf4jAuditSink.class)).isEmpty();
                });
    }
    /** The file path is required only when {@code hash-chained} is selected, and refused at the same phase as the rest of {@link DataPrismProperties#validate()} rather than surfacing as a {@code NullPointerException} once the bean is built. */
    @Test void refuses_hash_chained_sink_without_a_configured_file_path() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrationsWithoutAudit.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained")
                .run(result -> {
                    assertThat(result).hasFailed();
                    Throwable failure = rootCause(result.getStartupFailure());
                    assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
                    assertThat(failure.getMessage()).startsWith("MISSING_AUDIT_FILE_PATH:")
                            .contains("dataprism.audit.file-path");
                });
    }
    /**
     * {@code slf4j} still produces {@link Slf4jAuditSink} and never {@link
     * FileAuditSink}, and the two are never both registered: {@code
     * hasSingleBean(AuditSink.class)} fails outright if both conditional beans
     * were somehow active at once.
     */
    @Test void slf4j_sink_produces_slf4j_audit_sink_and_never_a_file_audit_sink() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrationsWithoutAudit.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.audit.sink=slf4j")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(AuditSink.class);
                    assertThat(result.getBean(AuditSink.class)).isInstanceOf(Slf4jAuditSink.class);
                    assertThat(result.getBeansOfType(FileAuditSink.class)).isEmpty();
                });
    }
    private void fails(String code, String override) { context.withPropertyValues(override).run(result -> { assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains(code); }); }
    private static String rootMessage(Throwable failure) { return rootCause(failure).getMessage(); }
    private static Throwable rootCause(Throwable failure) { Throwable current=failure; while(current.getCause()!=null) current=current.getCause(); return current; }
    private static String[] valid() { return new String[] {
            "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp", "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
            "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles", "dataprism.security.caller-claims.investigation=case_id",
            "dataprism.security-policy.purposes[0]=investigation", "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
            "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h", "dataprism.privacy.hmac-key.key-id=v1", "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
            "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test", "dataprism.metrics.sink=micrometer",
            "dataprism.hazelcast.topology=single-node",
            "dataprism.sources.customer.base-url=https://customer.example", "dataprism.sources.customer.timeout=2s"}; }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedIntegrations {
        @Bean DataSourceAdapter<String> customerAdapter() { return new DataSourceAdapter<>() { public String sourceName(){return "customer";} public Class<String> responseType(){return String.class;} public String fetch(DataRequest request){return null;} }; }
        @Bean IdentityResolver identities() { return new io.github.aindriub.dataprism.core.PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference+":"+id+":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> {}; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class ReviewedHttpIntegrations extends ReviewedIntegrations {
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }
    /**
     * {@link ReviewedHttpIntegrations} minus its {@code AuditSink} bean, so
     * {@code dataprism.audit.sink}'s own conditional beans in {@link
     * DataPrismAutoConfiguration.AuditSinkSelection} are the ones actually
     * exercised, rather than being suppressed by an application-supplied bean
     * via {@code @ConditionalOnMissingBean}.
     */
    @Configuration(proxyBeanMethods = false)
    static class ReviewedHttpIntegrationsWithoutAudit {
        @Bean DataSourceAdapter<String> customerAdapter() { return new ReviewedIntegrations().customerAdapter(); }
        @Bean IdentityResolver identities() { return new io.github.aindriub.dataprism.core.PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return new ReviewedIntegrations().keys(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithoutIdentity {
        @Bean DataSourceAdapter<String> customerAdapter() { return new ReviewedIntegrations().customerAdapter(); }
        @Bean HmacKeyReferenceResolver keys() { return new ReviewedIntegrations().keys(); }
        @Bean AuditSink audit() { return new ReviewedIntegrations().audit(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }
    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithoutAdapter {
        @Bean IdentityResolver identities() { return new io.github.aindriub.dataprism.core.PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return new ReviewedIntegrations().keys(); }
        @Bean AuditSink audit() { return new ReviewedIntegrations().audit(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }
    @Configuration(proxyBeanMethods = false)
    static class WeakKeyIntegrations extends ReviewedHttpIntegrations {
        @Override @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> "short".getBytes(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class UnresolvedKeyIntegrations extends ReviewedHttpIntegrations {
        @Override @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> { throw new IllegalStateException("not found"); }; }
    }
    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithCompetingSecretProvider extends ReviewedHttpIntegrations {
        @Bean SecretKeyProvider competingKeys() { return id -> "application-provider-must-not-win".getBytes(); }
    }
    /** Records its own construction; used by {@code refusal_happens_before_any_dataprism_singleton_is_constructed}. */
    static final class ConstructionMarker {
        static volatile boolean constructed;
        ConstructionMarker() { constructed = true; }
    }
    @Configuration(proxyBeanMethods = false)
    static class ReviewedIntegrationsWithConstructionMarker extends ReviewedIntegrations {
        @Bean ConstructionMarker constructionMarker() { return new ConstructionMarker(); }
    }
}
