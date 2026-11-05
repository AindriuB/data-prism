package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

    /** Several locations are an indexed list: a single value is never split. */
    private static StandardEnvironment environment(String... locations) {
        return environment(indexed(false, locations));
    }

    private static Map<String, Object> indexed(boolean fixtureDevelopment, String... locations) {
        Map<String, Object> properties = new HashMap<>();
        for (int i = 0; i < locations.length; i++) {
            properties.put("dataprism.json-sources.config-location[" + i + "]", locations[i]);
        }
        if (fixtureDevelopment) {
            properties.put("dataprism.transport.fixture-development", "true");
        }
        return properties;
    }

    @Test
    @DisplayName("a single location still works unchanged")
    void single() {
        assertThat(ConfiguredJsonSourcesInitializer.loadConfig(environment(A)).sources())
                .containsOnlyKeys("zeta-service");
    }

    @Test
    @DisplayName("several locations, as an indexed list, are joined; a comma-separated value is one location")
    void several() {
        assertThat(ConfiguredJsonSourcesInitializer.loadConfig(environment(A, B)).sources())
                .containsOnlyKeys("zeta-service", "alpha-service");
        assertThat(ConfiguredJsonSourcesInitializer.locations(environment(Map.of(PROPERTY, A + "," + B))))
                .containsExactly(A + "," + B);
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

    private static final String PROPERTY = "dataprism.json-sources.config-location";

    @Test
    @DisplayName("a single value is never split at commas, so a comma in user-info cannot put a fragment in a refusal")
    void singleValueIsNotSplit() {
        String credentialed = "https://svc:123,456@cfg.invalid/s.yaml";
        assertThat(ConfiguredJsonSourcesInitializer.locations(environment(Map.of(PROPERTY, credentialed))))
                .containsExactly(credentialed);
        assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(
                environment(Map.of(PROPERTY, credentialed))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PROPERTY + " '<location not shown>' could not be read")
                .hasMessageNotContaining("svc")
                .hasMessageNotContaining("123");

        String commaInPath = "file:/nowhere-synthetic/a,b/s.yaml";
        assertThat(ConfiguredJsonSourcesInitializer.locations(environment(Map.of(PROPERTY, commaInPath))))
                .containsExactly(commaInPath);
        assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(
                environment(Map.of(PROPERTY, commaInPath))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PROPERTY + " '" + commaInPath + "' could not be read");
    }

    @Test
    @DisplayName("beans are registered in source-name order, whatever the order of the files or of the map")
    void registrationOrderIsByName(@TempDir Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        StringBuilder yaml = new StringBuilder("json-sources:\n");
        for (int i = 0; i < 16; i++) {
            String name = "source-" + (char) ('a' + ((i * 7) % 16));
            names.add(name);
            yaml.append("  ").append(name).append(":\n")
                    .append("    base-url: https://").append(name).append(".example.internal\n")
                    .append("    path: /x/{subject}\n    timeout: PT2S\n    model-version: v1\n")
                    .append("    subject-json-path: ref\n    fields:\n      ref:\n        identifier: true\n");
        }
        Path file = directory.resolve("many.yaml");
        Files.writeString(file, yaml);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of(PROPERTY, file.toUri().toString())));
            new ConfiguredJsonSourcesInitializer().initialize(context);

            List<String> registered = new ArrayList<>();
            for (String bean : context.getBeanFactory().getSingletonNames()) {
                if (bean.startsWith("dataPrismConfiguredJsonSource_")) {
                    registered.add(bean.substring("dataPrismConfiguredJsonSource_".length()));
                }
            }
            assertThat(registered).containsExactlyElementsOf(names.stream().sorted().toList());
        }
    }

    private static final String TLS_A = "classpath:/task249-tls-a.yaml";
    private static final String TLS_B = "classpath:/task249-tls-b.yaml";
    private static final String PLAIN = "classpath:/task249-plain.yaml";

    @Test
    @DisplayName("`tls:` declared in two files refuses, naming neither file")
    void tlsInTwoFilesRefuses() {
        assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(environment(TLS_A, TLS_B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("json-sources `tls:` is declared in more than one file; declare it in exactly one");
    }

    @Test
    @DisplayName("`tls:` in one file refuses a plaintext source in another, naming the source and no location")
    void tlsRefusesPlaintextSourceInAnotherFile() {
        for (String[] order : new String[][] {{TLS_A, PLAIN}, {PLAIN, TLS_A}}) {
            assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(
                    environment(indexed(true, order))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("plain-one")
                    .hasMessageContaining("base-url must be https when any json-sources file configures `tls:`")
                    .hasMessageNotContaining("task249")
                    .hasMessageNotContaining("127.0.0.1");
        }
        // The same plaintext file alone, or with a file that has no `tls:`, is still allowed in fixture development.
        assertThat(ConfiguredJsonSourcesInitializer.loadConfig(environment(indexed(true, PLAIN, A))).sources())
                .containsOnlyKeys("plain-one", "zeta-service");
    }
}
