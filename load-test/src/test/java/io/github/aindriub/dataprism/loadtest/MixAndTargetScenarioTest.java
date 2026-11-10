package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixAndTargetScenarioTest {

    @TempDir
    Path dir;

    private Scenario load(String extra) throws IOException {
        Path file = dir.resolve("x.json");
        Files.writeString(file, "{\"name\":\"x\",\"sources\":5,\"load\":{\"concurrency\":1,\"calls\":3}" + extra + "}");
        return Scenario.load(file);
    }

    @Test
    void aMixAndATargetLoad() throws IOException {
        Scenario scenario = load(",\"mix\":{\"entityContext\":2,\"follow\":1},"
                + "\"target\":{\"baseUrl\":\"http://127.0.0.1:8080\",\"tokenUrl\":\"https://127.0.0.1:8544/token\"}");

        assertThat(scenario.mix().total()).isEqualTo(3);
        assertThat(scenario.mix().needsLinks()).isTrue();
        assertThat(scenario.target().entityType()).isEqualTo("PARCEL");
        assertThat(scenario.target().tokenLifetimeMinutes()).isEqualTo(60);
    }

    @Test
    void aScenarioWithoutAMixHasNone() throws IOException {
        assertThat(load("").mix()).isNull();
        assertThat(load("").target()).isNull();
    }

    @Test
    void anUnknownToolInTheMixIsRejected() {
        assertThatThrownBy(() -> load(",\"mix\":{\"entityContext\":1,\"deleteEverything\":1}"))
                .hasMessageContaining("deleteEverything");
    }

    @Test
    void anUnknownTargetFieldIsRejected() {
        assertThatThrownBy(() -> load(",\"target\":{\"baseUrl\":\"http://h:1\",\"tokenUrl\":\"http://h:2/t\",\"x\":1}"))
                .hasMessageContaining("\"x\"");
    }

    @Test
    void anEmptyOrNegativeMixIsRejected() {
        assertThatThrownBy(() -> load(",\"mix\":{}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight");
        assertThatThrownBy(() -> load(",\"mix\":{\"follow\":-1,\"entityContext\":1}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("follow");
    }

    @Test
    void aTargetNeedsAnOriginAndATokenUrl() {
        assertThatThrownBy(() -> load(",\"target\":{\"tokenUrl\":\"http://h:2/t\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> load(",\"target\":{\"baseUrl\":\"ftp://h\",\"tokenUrl\":\"http://h:2/t\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> load(",\"target\":{\"baseUrl\":\"http://h:1\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tokenUrl");
    }

    @Test
    void everyShippedScenarioLoadsAndTheSoakOnesMeetTheirAcceptanceShape() throws IOException {
        Path root = Path.of("scenarios");
        Path soak = Path.of("soak");
        try (Stream<Path> files = Stream.concat(Files.list(root), Files.list(soak))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                assertThat(Scenario.load(file)).as(file.toString()).isNotNull();
            }
        }

        Scenario single = Scenario.load(soak.resolve("soak-single-node.json"));
        assertThat(single.sources()).isEqualTo(100);
        assertThat(single.load().durationSeconds()).isGreaterThanOrEqualTo(8 * 3600);
        assertThat(single.load().ratePerSecond()).isPositive();
        assertThat(single.target()).isNull();
        assertThat(List.of(single.mix().entityContext(), single.mix().targetedEntityContext(),
                single.mix().listSources(), single.mix().describeSource(), single.mix().follow(),
                single.mix().invalidLink())).allMatch(weight -> weight > 0);

        Scenario cluster = Scenario.load(soak.resolve("soak-three-member.json"));
        assertThat(cluster.load().durationSeconds()).isGreaterThanOrEqualTo(4 * 3600);
        assertThat(cluster.target()).isNotNull();
        assertThat(cluster.mix().follow()).isZero();
        assertThat(cluster.mix().invalidLink()).isZero();
        assertThat(cluster.mix().needsLinks()).isFalse();
    }
}
