package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadTestMainTest {

    @Test
    void anOptionWithNoValueIsAUsageErrorNotAnArrayIndexError() {
        assertThatThrownBy(() -> LoadTestMain.parse(new String[] {"s.json", "--repo-root"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--repo-root");
        assertThatThrownBy(() -> LoadTestMain.parse(new String[] {"--results-dir"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--results-dir");
    }

    @Test
    void validArgumentsAreParsed() {
        LoadTestMain.Arguments arguments = LoadTestMain.parse(new String[] {"s.json", "--repo-root", "/r", "--results-dir", "/o"});

        assertThat(arguments.scenarioFile().toString()).isEqualTo("s.json");
        assertThat(arguments.repoRoot().toString()).isEqualTo("/r");
        assertThat(arguments.resultsDir().toString()).isEqualTo("/o");
    }
}
