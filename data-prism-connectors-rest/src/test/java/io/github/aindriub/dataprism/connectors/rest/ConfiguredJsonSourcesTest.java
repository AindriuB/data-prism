package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.FieldMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema/contract test for configuration-driven JSON REST sources: a
 * startup-time check, with a stable, named failure for every way this
 * configuration can be invalid or incomplete. See docs/pack.md's fail-closed
 * requirement and CLAUDE.md rule 6.
 */
class ConfiguredJsonSourcesTest {

    private static final String VALID = """
            json-sources:
              customer-api:
                base-url: https://customer.example
                path: /v1/customers/{subject}
                timeout: PT2S
                model-version: customer-v1
                subject-json-path: customerId
                fields:
                  customerId:
                    identifier: true
                  customerName:
                    classifications: [PII]
                    namespace: PERSON_NAME
                    action: SYNTHESIZE
                  email:
                    classifications: [CONTACT]
                    namespace: EMAIL
                    action: REDACT
                  status:
                    nonSensitive: "enumerated lifecycle state"
            """;

    private static InputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static ConfiguredJsonSourcesConfig load(String yaml) {
        return ConfiguredJsonSources.fromYaml(stream(yaml));
    }

    @Test
    @DisplayName("a fully specified source parses into a complete catalogue")
    void parsesAValidSource() {
        ConfiguredJsonSourcesConfig config = load(VALID);

        ConfiguredJsonSource source = config.sources().get("customer-api");
        assertThat(source).isNotNull();
        assertThat(source.modelVersion()).isEqualTo("customer-v1");
        assertThat(source.subjectField()).isEqualTo("customerId");
        assertThat(source.fields()).containsOnlyKeys("customerId", "customerName", "email", "status");

        FieldMetadata id = source.fields().get("customerId");
        assertThat(id.internalIdentifier()).isTrue();

        FieldMetadata name = source.fields().get("customerName");
        assertThat(name.classifications()).containsExactly(DataClassification.PII);
        assertThat(name.namespace()).isEqualTo(PrivacyNamespace.PERSON_NAME);
        assertThat(name.suggestedAction()).isEqualTo(PrivacyAction.SYNTHESIZE);

        FieldMetadata status = source.fields().get("status");
        assertThat(status.sensitive()).isFalse();
        assertThat(status.nonSensitiveReason()).isEqualTo("enumerated lifecycle state");
        assertThat(status.declared()).isTrue();
    }

    @Test
    @DisplayName("no json-sources section is a stable, named refusal")
    void noSectionRefuses() {
        assertThatThrownBy(() -> load("other: {}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("json-sources");
    }

    @Test
    @DisplayName("an unknown top-level key on a source refuses rather than being ignored")
    void unknownSourceKeyRefuses() {
        String yaml = VALID.replace("timeout: PT2S", "timeout: PT2S\n    extra: true");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key");
    }

    @Test
    @DisplayName("a missing timeout refuses; this mode never defaults one")
    void missingTimeoutRefuses() {
        String yaml = VALID.replace("    timeout: PT2S\n", "");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("a missing model-version refuses")
    void missingModelVersionRefuses() {
        String yaml = VALID.replace("    model-version: customer-v1\n", "");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-version");
    }

    @Test
    @DisplayName("a missing fields catalogue refuses")
    void missingFieldsRefuses() {
        String yaml = """
                json-sources:
                  customer-api:
                    base-url: https://customer.example
                    path: /v1/customers/{subject}
                    timeout: PT2S
                    model-version: customer-v1
                    subject-json-path: customerId
                """;
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields catalogue");
    }

    @Test
    @DisplayName("a subject-json-path outside the bounded grammar refuses")
    void subjectJsonPathMustBeABareFieldName() {
        for (String hostile : new String[] {
                "customerId.nested", "customer/id", "http://evil.example",
                "customerId?x=1", "customerId#frag", "../customerId", ""}) {
            String yaml = VALID.replace("subject-json-path: customerId", "subject-json-path: \"" + hostile + "\"");
            assertThatThrownBy(() -> load(yaml))
                    .as("hostile subject-json-path %s", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a subject-json-path naming a field the catalogue never declares refuses")
    void subjectJsonPathMustNameACataloguedField() {
        String yaml = VALID.replace("subject-json-path: customerId", "subject-json-path: nonExistentField");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not name a field");
    }

    @Test
    @DisplayName("a subject-json-path naming a classified, non-identifier field refuses")
    void subjectJsonPathCannotNameAClassifiedField() {
        String yaml = VALID.replace("subject-json-path: customerId", "subject-json-path: customerName");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifier: true");
    }

    @Test
    @DisplayName("a field stating both classifications and nonSensitive refuses")
    void fieldCannotBeBothSensitiveAndNonSensitive() {
        String yaml = VALID.replace(
                "      status:\n        nonSensitive: \"enumerated lifecycle state\"\n",
                "      status:\n        nonSensitive: \"enumerated lifecycle state\"\n"
                        + "        classifications: [PII]\n");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    @DisplayName("a field with no classification, reason or identifier marker refuses")
    void fieldMustStateSomething() {
        String yaml = VALID.replace(
                "      status:\n        nonSensitive: \"enumerated lifecycle state\"\n",
                "      status: {}\n");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    @DisplayName("an unknown classification enum value refuses, naming the offending value")
    void unknownClassificationRefuses() {
        String yaml = VALID.replace("classifications: [PII]", "classifications: [NOT_A_REAL_CLASSIFICATION]");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_A_REAL_CLASSIFICATION");
    }

    @Test
    @DisplayName("an unknown namespace enum value refuses")
    void unknownNamespaceRefuses() {
        String yaml = VALID.replace("namespace: PERSON_NAME", "namespace: NOT_A_REAL_NAMESPACE");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_A_REAL_NAMESPACE");
    }

    @Test
    @DisplayName("a field name outside the bounded grammar refuses")
    void fieldNameMustBeABareName() {
        String yaml = VALID.replace("customerName:", "customer.name:");
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a plaintext http base URL refuses by default, the same gate a Java-first "
            + "source's dataprism.sources.<name>.base-url must clear")
    void httpBaseUrlRefusedByDefault() {
        String yaml = VALID.replace("https://customer.example", "http://customer.example");
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("a base-url carrying user-info, a query string or a fragment refuses, "
            + "matching DataPrismProperties.trustedUri's clauses beyond the scheme check")
    void baseUrlCannotCarryUserInfoQueryOrFragment() {
        for (String hostile : new String[] {
                "https://ops@customer.example",
                "https://customer.example?k=v",
                "https://customer.example#frag"}) {
            String yaml = VALID.replace("https://customer.example", hostile);
            assertThatThrownBy(() -> load(yaml))
                    .as("hostile base-url %s", hostile)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("user-info, a query string or a fragment");
        }
    }

    @Test
    @DisplayName("a loopback http base URL is refused unless fixture-development says otherwise")
    void httpAllowedForLoopbackOnlyWhenFixtureDevelopment() {
        String yaml = VALID.replace("https://customer.example", "http://127.0.0.1:1");

        assertThatThrownBy(() -> ConfiguredJsonSources.fromYaml(stream(yaml), false))
                .as("fixture-development not set")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> load(yaml))
                .as("the single-argument overload defaults to false, the same as production")
                .isInstanceOf(IllegalArgumentException.class);

        ConfiguredJsonSourcesConfig config = ConfiguredJsonSources.fromYaml(stream(yaml), true);
        assertThat(config.sources().get("customer-api").transport().baseUrl().toString())
                .isEqualTo("http://127.0.0.1:1");
    }

    @Test
    @DisplayName("fixture-development does not widen the exception past a loopback host")
    void nonLoopbackHttpRefusedEvenWithFixtureDevelopment() {
        String yaml = VALID.replace("https://customer.example", "http://customer.example");
        assertThatThrownBy(() -> ConfiguredJsonSources.fromYaml(stream(yaml), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("a tls: block closes the loopback exception even under fixture-development")
    void tlsBlockClosesTheLoopbackException() throws Exception {
        java.nio.file.Path store = java.nio.file.Files.createTempFile("dp-json-source-tls", ".p12");
        String yaml = VALID.replace("https://customer.example", "http://127.0.0.1:1") + """
                tls:
                  key-store: %s
                  key-store-password-env: DATA_PRISM_TEST_TLS_PASSWORD
                  trust-store: %s
                  trust-store-password-env: DATA_PRISM_TEST_TLS_PASSWORD
                  store-type: PKCS12
                """.formatted(store, store);
        assertThatThrownBy(() -> ConfiguredJsonSources.fromYaml(stream(yaml), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("duplicate identifier markers in one catalogue refuse")
    void duplicateIdentifiersRefuse() {
        String yaml = """
                json-sources:
                  customer-api:
                    base-url: https://customer.example
                    path: /v1/customers/{subject}
                    timeout: PT2S
                    model-version: customer-v1
                    subject-json-path: customerId
                    fields:
                      customerId:
                        identifier: true
                      email:
                        identifier: true
                """;
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one is required");
    }
}
