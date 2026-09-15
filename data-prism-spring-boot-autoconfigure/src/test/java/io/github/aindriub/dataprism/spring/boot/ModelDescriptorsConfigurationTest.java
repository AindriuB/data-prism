package io.github.aindriub.dataprism.spring.boot;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires an optional {@code dataprism.privacy.descriptor-file} into the
 * {@link FieldMetadataResolver} bean. Unset, nothing changes; set, it must load
 * and validate eagerly, and any bad input refuses startup rather than silently
 * keeping the annotation-only resolver — see {@code DataPrismAutoConfiguration
 * #dataPrismFieldMetadataResolver}.
 */
class ModelDescriptorsConfigurationTest {

    private static final String PACKAGE = ModelDescriptorsConfigurationTest.class.getName();

    /** Stands in for a generated or third-party DTO: no annotations at all. */
    record DescriptorOnlyModel(String id, String name, String status) {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    @Test
    void unset_property_leaves_the_default_resolver_in_place() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(FieldMetadataResolver.class))
                    .isExactlyInstanceOf(DefaultFieldMetadataResolver.class);
        });
    }

    @Test
    void a_readable_descriptor_file_wraps_the_default_resolver_and_scrubs_accordingly(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("models.yaml");
        Files.writeString(file, """
                models:
                  %s$DescriptorOnlyModel:
                    exposed: true
                    undeclaredFields: DROP
                    fields:
                      id: { identifier: self }
                      name: { classifications: [CONFIDENTIAL] }
                      status: { nonSensitive: enumerated lifecycle state }
                """.formatted(PACKAGE));

        context.withPropertyValues("dataprism.privacy.descriptor-file=" + file)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    FieldMetadataResolver resolver = result.getBean(FieldMetadataResolver.class);
                    assertThat(resolver).isInstanceOf(DescriptorFieldMetadataResolver.class);

                    JsonTreeScrubbingEngine engine = result.getBean(JsonTreeScrubbingEngine.class);
                    PseudonymisationVersion version = result.getBean(PseudonymisationVersion.class);
                    ObjectNode out = engine.scrub(
                            new DescriptorOnlyModel("id-1", "confidential-name", "ACTIVE"),
                            new PrivacyContext("C-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                                    Instant.parse("2030-01-01T00:00:00Z"), version)).tree();

                    assertThat(out.get("name").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
                    assertThat(out.get("status").asText()).isEqualTo("ACTIVE");
                    assertThat(out.has("id")).isFalse();
                    assertThat(out.toString()).doesNotContain("confidential-name");
                });
    }

    @Test
    void refuses_a_descriptor_file_that_does_not_exist(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.yaml");
        fails("MODEL_DESCRIPTOR_FILE_NOT_FOUND", missing.toString());
    }

    @Test
    void refuses_a_descriptor_file_that_cannot_be_read_as_a_file(@TempDir Path dir) {
        // A directory is not a regular, readable file: this exercises the refusal
        // deterministically, without depending on filesystem permission support.
        fails("MODEL_DESCRIPTOR_FILE_UNREADABLE", dir.toString());
    }

    @Test
    void refuses_a_descriptor_file_with_no_models_section(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("no-models.yaml");
        Files.writeString(file, "not-models: true\n");
        fails("INVALID_MODEL_DESCRIPTOR_FILE", file.toString());
    }

    @Test
    void refuses_a_descriptor_that_declares_undeclared_fields_non_sensitive(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("unsafe.yaml");
        Files.writeString(file, """
                models:
                  %s$DescriptorOnlyModel:
                    exposed: true
                    undeclaredFields: NON_SENSITIVE
                """.formatted(PACKAGE));
        fails("UNSAFE_MODEL_DESCRIPTOR_UNDECLARED_FIELDS", file.toString());
    }

    private void fails(String code, String descriptorFile) {
        context.withPropertyValues("dataprism.privacy.descriptor-file=" + descriptorFile).run(result -> {
            assertThat(result).hasFailed();
            String message = rootMessage(result.getStartupFailure());
            assertThat(message).contains(code);
            assertThat(message).doesNotContain(PACKAGE);
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
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
                "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                "dataprism.metrics.sink=micrometer", "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s" };
    }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedIntegrations {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() {
                    return "customer";
                }

                public Class<String> responseType() {
                    return String.class;
                }

                public String fetch(DataRequest request) {
                    return null;
                }
            };
        }

        @Bean
        IdentityResolver identities() {
            return new io.github.aindriub.dataprism.core.PassThroughIdentityResolver();
        }

        @Bean
        HmacKeyReferenceResolver keys() {
            return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes();
        }

        @Bean
        AuditSink audit() {
            return event -> {
            };
        }

        @Bean
        PrivacyMetrics metrics() {
            return PrivacyMetrics.none();
        }
    }
}
