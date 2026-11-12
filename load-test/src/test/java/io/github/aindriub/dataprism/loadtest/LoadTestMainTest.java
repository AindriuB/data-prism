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

    @Test
    void memberBuildIsUnrecordedWhenTheTargetIsNotTheLocalOverlay() {
        assertThat(LoadTestMain.memberBuild("https://target.example.test", () -> "feed source=ab commit=c dirty=no"))
                .isEqualTo("unrecorded");
        assertThat(LoadTestMain.memberBuild("http://10.1.2.3:8080", () -> "x")).isEqualTo("unrecorded");
        assertThat(LoadTestMain.memberBuild("http://127.0.0.1:8080", () -> "feed source=ab commit=c dirty=no"))
                .isEqualTo("feed source=ab commit=c dirty=no");
        assertThat(LoadTestMain.memberBuild("http://localhost:8080", () -> "unknown")).isEqualTo("unknown");
    }
}
