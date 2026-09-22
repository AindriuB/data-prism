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
import java.util.Map;

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

    // --- one level of named nested catalogues -----------------------------

    private static final String VALID_WITH_NESTED = """
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
                  address:
                    nested: address
                nested-catalogues:
                  address:
                    line1:
                      nonSensitive: "street address line, reviewed as inert structure"
                    postalCode:
                      classifications: [PII]
                      namespace: ADDRESS
                      action: REDACT
            """;

    @Test
    @DisplayName("a field naming a declared nested catalogue parses, and the catalogue holds exactly its declared entries")
    void nestedCatalogueParses() {
        ConfiguredJsonSourcesConfig config = load(VALID_WITH_NESTED);
        ConfiguredJsonSource source = config.sources().get("customer-api");

        assertThat(source.fields()).containsKey("address");
        assertThat(source.nestedCatalogues()).containsOnlyKeys("address");

        Map<String, FieldMetadata> address = source.nestedCatalogues().get("address");
        assertThat(address).containsOnlyKeys("line1", "postalCode");

        FieldMetadata line1 = address.get("line1");
        assertThat(line1.declared()).isTrue();
        assertThat(line1.nonSensitiveReason()).isEqualTo("street address line, reviewed as inert structure");

        FieldMetadata postalCode = address.get("postalCode");
        assertThat(postalCode.classifications()).containsExactly(DataClassification.PII);
        assertThat(postalCode.namespace()).isEqualTo(PrivacyNamespace.ADDRESS);
    }

    @Test
    @DisplayName("startup refusal: `nested:` names no declared nested-catalogues entry")
    void nestedFieldWithNoMatchingCatalogueRefuses() {
        String yaml = VALID_WITH_NESTED.replace("nested: address", "nested: doesNotExist");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("doesNotExist")
                .hasMessageContaining("does not name an entry declared");
    }

    @Test
    @DisplayName("startup refusal: a nested catalogue is declared but referenced by no field")
    void nestedCatalogueDeclaredButUnreferencedRefuses() {
        String yaml = VALID_WITH_NESTED.replace("      address:\n        nested: address\n", "");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("address")
                .hasMessageContaining("referenced by no field");
    }

    @Test
    @DisplayName("startup refusal: a nested catalogue entry states `nested:`, which is not one more level")
    void nestedCatalogueCannotContainNested() {
        String yaml = VALID_WITH_NESTED.replace(
                "      address:\n        line1:\n"
                        + "          nonSensitive: \"street address line, reviewed as inert structure\"\n",
                "      address:\n        line1:\n          nested: address\n");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("nested catalogue")
                .hasMessageContaining("nesting is exactly one level deep");
    }

    @Test
    @DisplayName("startup refusal: a nested catalogue entry states more than one shape")
    void nestedCatalogueEntryMustStateExactlyOneShape() {
        String yaml = VALID_WITH_NESTED.replace(
                "        line1:\n"
                        + "          nonSensitive: \"street address line, reviewed as inert structure\"\n",
                "        line1:\n"
                        + "          nonSensitive: \"street address line, reviewed as inert structure\"\n"
                        + "          classifications: [PII]\n");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("exactly one of");
    }

    @Test
    @DisplayName("startup refusal: a nested catalogue entry states none of the three shapes")
    void nestedCatalogueEntryMustStateSomething() {
        String yaml = VALID_WITH_NESTED.replace(
                "        line1:\n"
                        + "          nonSensitive: \"street address line, reviewed as inert structure\"\n",
                "        line1: {}\n");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("exactly one of");
    }

    @Test
    @DisplayName("startup refusal: an operator nonSensitive reason that begins with the reserved "
            + "nested-catalogue marker refuses, naming the source and field")
    void nonSensitiveReasonCannotBeMistakenForANestedPointer() {
        String yaml = VALID_WITH_NESTED.replace(
                "      status:\n        nonSensitive: \"enumerated lifecycle state\"\n",
                "      status:\n        nonSensitive: \"nested catalogue address\"\n");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api, field status")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("status")
                .hasMessageContaining("reserved marker");
    }

    @Test
    @DisplayName("startup refusal: a nested catalogue entry marks identifier: true")
    void nestedCatalogueEntryCannotBeIdentifier() {
        String yaml = VALID_WITH_NESTED.replace(
                "        line1:\n"
                        + "          nonSensitive: \"street address line, reviewed as inert structure\"\n",
                "        line1:\n          identifier: true\n");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("nested catalogue")
                .hasMessageContaining("no identifier of its own");
    }

    @Test
    @DisplayName("startup refusal: a nested catalogue name is outside the bounded FIELD_NAME grammar")
    void nestedCatalogueNameMustBeABareName() {
        String yaml = VALID_WITH_NESTED.replace("      address:\n        line1:",
                "      address.sub:\n        line1:");
        assertThatThrownBy(() -> load(yaml))
                .as("source customer-api")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer-api")
                .hasMessageContaining("not a bare property name");
    }

    @Test
    @DisplayName("equals/hashCode are identical between two independent parses of identical YAML")
    void equalsAndHashCodeAreParseIndependent() {
        // ConfiguredJsonSource no longer carries a second, Class-keyed copy of
        // the nested catalogue index on its public shape (see docs/plan/tasks/
        // 60-*.md attempt 1, defect 3); nestedCatalogues() alone determines
        // equality, so two parses of the same YAML must compare equal.
        ConfiguredJsonSource first = load(VALID_WITH_NESTED).sources().get("customer-api");
        ConfiguredJsonSource second = load(VALID_WITH_NESTED).sources().get("customer-api");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    @DisplayName("startup refusal: a source declaring more nested catalogues than the "
            + "pre-declared token pool holds is refused, naming the source")
    void moreNestedCataloguesThanThePoolHoldsRefuses() {
        int poolSize = ConfiguredJsonNestedCatalogueTokens.POOL.size();
        StringBuilder yaml = new StringBuilder();
        yaml.append("json-sources:\n")
                .append("  overflowing-source:\n")
                .append("    base-url: https://customer.example\n")
                .append("    path: /v1/customers/{subject}\n")
                .append("    timeout: PT2S\n")
                .append("    model-version: customer-v1\n")
                .append("    subject-json-path: customerId\n")
                .append("    fields:\n")
                .append("      customerId:\n")
                .append("        identifier: true\n");
        for (int i = 0; i <= poolSize; i++) {
            yaml.append("      cat").append(i).append(":\n")
                    .append("        nested: cat").append(i).append("\n");
        }
        yaml.append("    nested-catalogues:\n");
        for (int i = 0; i <= poolSize; i++) {
            yaml.append("      cat").append(i).append(":\n")
                    .append("        leaf:\n")
                    .append("          nonSensitive: \"inert\"\n");
        }

        assertThatThrownBy(() -> load(yaml.toString()))
                .as("source overflowing-source")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflowing-source")
                .hasMessageContaining(String.valueOf(poolSize));
    }
}
