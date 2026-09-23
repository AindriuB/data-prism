package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditChainVerifier;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.FileAuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the end of task 67's wiring, not just that a bean is created: a real
 * {@link AuditRecorder} obtained from a context configured with {@code
 * dataprism.audit.sink=hash-chained} writes to the configured file, and the
 * chain that file holds is intact — verified by task 66's own {@link
 * AuditChainVerifier}, the reader the task file names, rather than by
 * replaying the chain by hand.
 */
class HashChainedAuditSinkTest {

    @Test
    void a_context_configured_with_hash_chained_writes_a_file_with_an_intact_chain(@TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("audit.log");

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ReviewedHttpIntegrationsWithoutAudit.class)
                .withPropertyValues(valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained", "dataprism.audit.file-path=" + file)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuditSink.class)).isInstanceOf(FileAuditSink.class);

                    AuditRecorder recorder = context.getBean(AuditRecorder.class);
                    for (int i = 0; i < 3; i++) {
                        recorder.record("principal-" + i, "client", "get_entity_context", "CUSTOMER",
                                "pseudonym-" + i, "fingerprint", "DEFAULT", "scope-" + i, "investigation",
                                "case-1", "ALLOW", Set.of("customer"), Set.of(), "correlation-" + i);
                    }
                });

        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).hasSize(3);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);

        assertThat(report.hasBreak()).as("chain must verify intact, no break").isFalse();
        assertThat(report.hasStructuralAnomaly()).as("chain must verify intact, no structural anomaly").isFalse();
        assertThat(report.writers()).hasSize(1);
        assertThat(report.writers().get(0).sequenceCount()).isEqualTo(3);
        assertThat(report.writers().get(0).broken()).isFalse();
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                "dataprism.audit.writer-id=test", "dataprism.metrics.sink=micrometer",
                "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    /**
     * Mirrors {@link DataPrismAutoConfigurationTest.ReviewedHttpIntegrationsWithoutAudit}:
     * every reviewed integration except {@code AuditSink}, so the sink actually
     * exercised is the one {@code dataprism.audit.sink=hash-chained} produces.
     */
    @Configuration(proxyBeanMethods = false)
    static class ReviewedHttpIntegrationsWithoutAudit {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(io.github.aindriub.dataprism.core.DataRequest request) { return null; }
            };
        }

        @Bean
        IdentityResolver identities() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        HmacKeyReferenceResolver keys() {
            return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(StandardCharsets.UTF_8);
        }

        @Bean
        PrivacyMetrics metrics() {
            return PrivacyMetrics.none();
        }

        @Bean
        McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }
}
