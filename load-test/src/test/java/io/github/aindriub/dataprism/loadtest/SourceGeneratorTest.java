package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceGeneratorTest {

    private static Scenario scenario(int sources, int minMs, int maxMs) {
        return new Scenario("t", sources, new Scenario.Latency(minMs, maxMs),
                new Scenario.Load(2, 10, 0, 0, 0, 30, 0), Scenario.Limits.defaults(sources), 7L, 0, null);
    }

    @Test
    void generatesExactlyNSourcesWithDistinctNamesAndPaths() {
        for (int n : new int[] {1, 8, 32, 100, 300, 500}) {
            List<SourceDefinition> sources = SourceGenerator.generate(scenario(n, 5, 40));

            assertThat(sources).hasSize(n);
            assertThat(new HashSet<>(sources.stream().map(SourceDefinition::name).toList())).hasSize(n);
            assertThat(new HashSet<>(sources.stream().map(SourceDefinition::path).toList())).hasSize(n);
            assertThat(sources).allSatisfy(s -> assertThat(s.path()).contains("{subject}"));
        }
    }

    @Test
    void everySourceStatesTheScenariosLatencyDistribution() {
        List<SourceDefinition> sources = SourceGenerator.generate(scenario(8, 5, 40));

        assertThat(sources).allSatisfy(s -> {
            assertThat(s.latencyMinMs()).isEqualTo(5);
            assertThat(s.latencyMaxMs()).isEqualTo(40);
        });
    }

    @Test
    void sampledLatencyStaysInsideTheStatedBandAndIsReproducible() {
        SourceDefinition source = SourceGenerator.generate(scenario(2, 5, 40)).get(1);

        for (long call = 0; call < 500; call++) {
            int sample = source.sampleLatencyMs(7L, call);
            assertThat(sample).isBetween(5, 40);
            assertThat(source.sampleLatencyMs(7L, call)).isEqualTo(sample);
        }
    }

    @Test
    void aFixedLatencyBandAlwaysSamplesThatValue() {
        SourceDefinition source = SourceGenerator.generate(scenario(1, 12, 12)).get(0);

        assertThat(source.sampleLatencyMs(1L, 99)).isEqualTo(12);
    }

    @Test
    void rejectsAScenarioWithNoSourcesOrAnInvertedBand() {
        assertThatThrownBy(() -> SourceGenerator.generate(scenario(0, 5, 40)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceGenerator.generate(scenario(3, 40, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedConfigurationNamesEverySourceAndOnlyClassifiedFields() {
        List<SourceDefinition> sources = SourceGenerator.generate(scenario(3, 1, 2));

        String yaml = SourceGenerator.catalogueYaml(sources, "http://127.0.0.1:9");

        assertThat(yaml).startsWith("json-sources:");
        sources.forEach(s -> assertThat(yaml).contains("  " + s.name() + ":").contains(s.path()));
        assertThat(yaml).contains("base-url: http://127.0.0.1:9").contains("identifier: true");
    }
}
