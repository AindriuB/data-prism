package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioTest {

    @TempDir
    Path dir;

    @Test
    void omittedLimitsTakeTheFrameworksDocumentedDefaultsExceptSources() throws Exception {
        Path file = dir.resolve("x.json");
        Files.writeString(file, """
                {"name":"x","sources":5,"sourceLatency":{"minMs":1,"maxMs":2},"load":{"concurrency":1,"calls":3}}
                """);

        Scenario scenario = Scenario.load(file);

        assertThat(scenario.limits().maxSources()).isEqualTo(5);
        assertThat(scenario.limits().asProperties()).containsEntry("dataprism.limits.max-records", "500")
                .containsEntry("dataprism.limits.max-response-size", "512KB")
                .containsEntry("dataprism.limits.per-source-timeout", "3s")
                .containsEntry("dataprism.limits.max-concurrency", "4")
                .containsEntry("dataprism.limits.scope-read-budget", "100")
                .containsEntry("dataprism.limits.max-sources", "5");
    }

    @Test
    void refusesAScenarioWithNoWorkToDo() throws Exception {
        Path file = dir.resolve("x.json");
        Files.writeString(file, """
                {"name":"x","sources":5,"sourceLatency":{"minMs":1,"maxMs":2},"load":{"concurrency":1}}
                """);

        assertThatThrownBy(() -> Scenario.load(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("calls");
    }

    @Test
    void rejectsNegativeLoadValuesWithAMessageNamingTheField() throws Exception {
        for (String field : List.of("ratePerSecond", "warmupCalls", "calls", "durationSeconds")) {
            Path file = dir.resolve("x.json");
            Files.writeString(file, """
                    {"name":"x","sources":5,"load":{"concurrency":1,"calls":3,"%s":-1}}
                    """.formatted(field));

            assertThatThrownBy(() -> Scenario.load(file)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(field).hasMessageContaining("negative");
        }
    }

    @Test
    void rejectsANameThatIsNotASafeFileNameOrDoesNotMatchTheFile() throws Exception {
        for (String name : List.of("../evil", "a/b", "A", "x y", "", "-x")) {
            Path file = dir.resolve("x.json");
            Files.writeString(file, """
                    {"name":"%s","sources":5,"load":{"concurrency":1,"calls":3}}
                    """.formatted(name));

            assertThatThrownBy(() -> Scenario.load(file)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }
        Path mismatched = dir.resolve("other.json");
        Files.writeString(mismatched, """
                {"name":"x","sources":5,"load":{"concurrency":1,"calls":3}}
                """);

        assertThatThrownBy(() -> Scenario.load(mismatched)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.json");
    }

    @Test
    void theShippedScenariosLoadAndHaveTheirNamedSourceCounts() throws Exception {
        for (int n : List.of(8, 32, 100, 300)) {
            Path file = Path.of("scenarios", "sources-" + n + ".json");
            Scenario scenario = Scenario.load(file);

            assertThat(scenario.sources()).isEqualTo(n);
            assertThat(scenario.limits().maxSources()).isGreaterThanOrEqualTo(n);
            assertThat(SourceGenerator.generate(scenario)).hasSize(n);
            assertThat(scenario.name()).isEqualTo(file.getFileName().toString().replace(".json", ""));
        }
    }

    @Test
    void theFormatAllowsADurationDrivenSoakAndFiveHundredSources() throws Exception {
        Path file = dir.resolve("soak.json");
        Files.writeString(file, """
                {"name":"soak","sources":500,"sourceLatency":{"minMs":5,"maxMs":50},
                 "load":{"concurrency":16,"durationSeconds":3600,"ratePerSecond":20},
                 "limits":{"maxSources":500}}
                """);

        Scenario scenario = Scenario.load(file);

        assertThat(scenario.load().durationSeconds()).isEqualTo(3600);
        assertThat(scenario.load().ratePerSecond()).isEqualTo(20);
        assertThat(SourceGenerator.generate(scenario)).hasSize(500);
    }

    @Test
    void noShippedScenarioFileContainsAKeyOrToken() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("scenarios"))) {
            for (Path file : files.toList()) {
                assertThat(Files.readString(file).toLowerCase()).doesNotContain("password", "secret", "token", "bearer");
            }
        }
    }
}
