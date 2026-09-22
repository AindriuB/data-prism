package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import io.github.aindriub.dataprism.audit.AuditRecordFormat;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the end of task 67's wiring, not just that a bean is created: a real
 * {@link AuditRecorder} obtained from a context configured with {@code
 * dataprism.audit.sink=hash-chained} writes to the configured file, and the
 * chain that file holds is intact — recomputed with the same {@link
 * AuditEventHash} task 66's verifier would use, since that verifier is not yet
 * merged (see the class-level note below on what this test stands in for).
 *
 * <p>Task 66's own {@code AuditChainVerifier} is what the task file names as
 * the intended reader of this file; it does not exist yet in this repository
 * (task 66 is unmerged), so this test instead replays the chain itself, using
 * the same {@link AuditRecordFormat} and {@link AuditEventHash} the verifier
 * would be built on. When task 66 lands, its own test suite is the place a
 * true end-to-end check belongs; this test's job is only to prove task 67's
 * wiring produces a file task 66's verifier — once it exists — will be able to
 * read.
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

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);

        String expectedPrevious = "0".repeat(64);
        long expectedSequence = 1;
        for (String line : lines) {
            AuditEvent event = AuditRecordFormat.parse(line);

            assertThat(event.sequence()).isEqualTo(expectedSequence);
            assertThat(event.previousHash())
                    .as("record %d must chain against the previous record's hash", expectedSequence)
                    .isEqualTo(expectedPrevious);
            assertThat(event.eventHash())
                    .as("record %d's stored hash must match what AuditEventHash recomputes from its own fields",
                            expectedSequence)
                    .isEqualTo(AuditEventHash.compute(event));

            expectedPrevious = event.eventHash();
            expectedSequence++;
        }
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
