package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code dataprism.json-sources.config-location} takes a list of files (task 249, OD-08-8 (a)). */
class ConfiguredJsonSourceFilesTest {

    private static final String A = "classpath:/task249-files-a.yaml";
    private static final String B = "classpath:/task249-files-b.yaml";
    private static final String DUP = "classpath:/task249-files-dup.yaml";

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static StandardEnvironment environment(String... locations) {
        return environment(Map.of("dataprism.json-sources.config-location", String.join(",", locations)));
    }

    @Test
    @DisplayName("a single location still works unchanged")
    void single() {
        assertThat(ConfiguredJsonSourcesInitializer.loadConfig(environment(A)).sources())
                .containsOnlyKeys("zeta-service");
    }

    @Test
    @DisplayName("several locations, comma-separated or indexed, are joined")
    void several() {
        assertThat(ConfiguredJsonSourcesInitializer.loadConfig(environment(A, B)).sources())
                .containsOnlyKeys("zeta-service", "alpha-service");
        assertThat(ConfiguredJsonSourcesInitializer.loadConfig(environment(Map.of(
                "dataprism.json-sources.config-location[0]", A,
                "dataprism.json-sources.config-location[1]", B))).sources())
                .containsOnlyKeys("zeta-service", "alpha-service");
    }

    @Test
    @DisplayName("the sources do not depend on the order of the files")
    void orderOfFilesDecidesNothing() {
        var forward = ConfiguredJsonSourcesInitializer.loadConfig(environment(A, B)).sources();
        var backward = ConfiguredJsonSourcesInitializer.loadConfig(environment(B, A)).sources();
        assertThat(backward).isEqualTo(forward);
        assertThat(List.copyOf(ConfiguredJsonSourcesInitializer.locations(environment(B, A)))).containsExactly(B, A);
    }

    @Test
    @DisplayName("a source name in two files refuses with DUPLICATE_JSON_SOURCE, naming the source and no location")
    void duplicateRefuses() {
        for (StandardEnvironment env : List.of(environment(A, DUP), environment(DUP, A), environment(A, B, DUP))) {
            assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("DUPLICATE_JSON_SOURCE:")
                    .hasMessageContaining("zeta-service")
                    .hasMessageNotContaining("task249")
                    .hasMessageNotContaining("classpath");
        }
    }

    @Test
    @DisplayName("an unreadable file refuses as before, with the location shown by describe() only")
    void unreadable() {
        assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(
                environment(A, "classpath:/missing.yaml?token=SECRET-VALUE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dataprism.json-sources.config-location 'classpath:/missing.yaml' could not be read")
                .hasNoCause();
    }

    @Test
    @DisplayName("a file that breaks the strict parser still refuses when it is not the first")
    void everyFileIsParsedStrictly() {
        assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(
                environment(A, "classpath:/task249-files-bad.yaml")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_CONFIG_KEY");
    }

    @Test
    @DisplayName("a blank location is treated as unset")
    void blankIsUnset() {
        assertThat(ConfiguredJsonSourcesInitializer.locations(environment(" "))).isEmpty();
    }
}
