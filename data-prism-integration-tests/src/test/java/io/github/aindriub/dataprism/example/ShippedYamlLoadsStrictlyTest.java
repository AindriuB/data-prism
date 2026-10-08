package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonSources;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every YAML file the repository ships as configuration input, and that no other test loads, still loads now that the
 * readers refuse duplicate keys, unknown keys, trailing content and wrong-typed scalars. The files under
 * {@code data-prism-connectors-rest/src/test/resources} and
 * {@code data-prism-server/src/test/resources/configured-json} are loaded by their own modules' tests.
 */
class ShippedYamlLoadsStrictlyTest {

    /** The reactor root, from the module directory surefire runs this test in. */
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    @DisplayName("the shipped default privacy profiles load")
    void defaultPrivacyProfiles() throws IOException {
        try (InputStream in = Files.newInputStream(
                ROOT.resolve("data-prism-core/src/main/resources/privacy-profiles-default.yaml"))) {
            assertThat(PrivacyProfiles.fromYaml(in)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("all seven bundled vocabularies load")
    void bundledVocabularies() {
        assertThat(VocabularyRegistry.withBuiltIns().locales()).contains("und", "en", "mul", "ga", "ar", "zh", "ru");
    }

    @Test
    @DisplayName("the example customer-api configured JSON source loads")
    void customerApiExample() throws IOException {
        try (InputStream in = Files.newInputStream(ROOT.resolve("examples/json-sources/customer-api.yaml"))) {
            assertThat(ConfiguredJsonSources.fromYaml(in).sources()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("the example nested-catalogue configured JSON source loads")
    void customerApiNestedExample() throws IOException {
        try (InputStream in = Files.newInputStream(ROOT.resolve("examples/json-sources/customer-api-nested.yaml"))) {
            assertThat(ConfiguredJsonSources.fromYaml(in).sources()).isNotEmpty();
        }
    }
}
