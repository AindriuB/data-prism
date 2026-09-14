package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class DataPrismAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    @Test void boots_a_minimal_reviewed_context() {
        context.withPropertyValues("dataprism.transport.mode=stdio",
                "dataprism.transport.fixture-development=true")
                .run(result -> assertThat(result).hasNotFailed());
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
    @Test void fixture_stdio_creates_no_http_transport_server_or_servlet_even_with_an_extractor() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrations.class).withPropertyValues(valid())
                .withPropertyValues("dataprism.transport.mode=stdio",
                        "dataprism.transport.fixture-development=true")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).doesNotHaveBean(DataPrismMcpServer.HttpTransport.class)
                            .doesNotHaveBean(McpSyncServer.class)
                            .doesNotHaveBean("dataPrismMcpServlet");
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
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
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
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(WeakKeyIntegrations.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("HMAC_KEY_WEAK");
                });
    }
    @Test void refuses_an_unresolved_configured_key_reference() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(UnresolvedKeyIntegrations.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("HMAC_KEY_UNRESOLVED");
                });
    }
    @Test void refuses_an_unresolved_identity_resolver() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(IntegrationsWithoutIdentity.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("MISSING_IDENTITY_RESOLVER");
                });
    }
    @Test void refuses_an_unresolved_source_adapter() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(IntegrationsWithoutAdapter.class).withPropertyValues(valid()).run(result -> {
                    assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains("UNRESOLVED_SOURCE_ADAPTER");
                });
    }
    private void fails(String code, String override) { context.withPropertyValues(override).run(result -> { assertThat(result).hasFailed(); assertThat(rootMessage(result.getStartupFailure())).contains(code); }); }
    private static String rootMessage(Throwable failure) { Throwable current=failure; while(current.getCause()!=null) current=current.getCause(); return current.getMessage(); }
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
    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithoutIdentity {
        @Bean DataSourceAdapter<String> customerAdapter() { return new ReviewedIntegrations().customerAdapter(); }
        @Bean HmacKeyReferenceResolver keys() { return new ReviewedIntegrations().keys(); }
        @Bean AuditSink audit() { return new ReviewedIntegrations().audit(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithoutAdapter {
        @Bean IdentityResolver identities() { return new io.github.aindriub.dataprism.core.PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return new ReviewedIntegrations().keys(); }
        @Bean AuditSink audit() { return new ReviewedIntegrations().audit(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class WeakKeyIntegrations extends ReviewedIntegrations {
        @Override @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> "short".getBytes(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class UnresolvedKeyIntegrations extends ReviewedIntegrations {
        @Override @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> { throw new IllegalStateException("not found"); }; }
    }
    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithCompetingSecretProvider extends ReviewedIntegrations {
        @Bean SecretKeyProvider competingKeys() { return id -> "application-provider-must-not-win".getBytes(); }
    }
}
