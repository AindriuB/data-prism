package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerProcessTest {

    @TempDir
    Path dir;

    @Test
    void theLogMustShowTheChildBoundTheExpectedPort() throws Exception {
        Path log = dir.resolve("server.log");
        Files.writeString(log, "x\nTomcat started on port 41234 (http) with context path '/'\n");

        assertThat(ServerProcess.logShowsPort(log, 41234)).isTrue();
        assertThat(ServerProcess.logShowsPort(log, 4123)).isFalse();
        assertThat(ServerProcess.logShowsPort(log, 1234)).isFalse();
        assertThat(ServerProcess.logShowsPort(dir.resolve("missing.log"), 41234)).isFalse();
    }

    @Test
    void theServersOwnLogIsCappedAndItsConsoleIsSilenced() {
        java.util.List<String> arguments = ServerProcess.loggingArguments(dir.resolve("server.log"));

        assertThat(arguments).contains("--logging.file.name=" + dir.resolve("server.log"))
                .contains("--logging.pattern.console=")
                .anyMatch(a -> a.startsWith("--logging.logback.rollingpolicy.max-file-size="))
                .anyMatch(a -> a.startsWith("--logging.logback.rollingpolicy.max-history="))
                .anyMatch(a -> a.startsWith("--logging.logback.rollingpolicy.total-size-cap="));
    }

    @Test
    void keptLogsIncludeTheRolledFilesNextToTheLiveOne() throws Exception {
        Files.writeString(dir.resolve("server.log"), "live");
        Files.writeString(dir.resolve("server.log.1.gz"), "old");
        Files.writeString(dir.resolve("other.txt"), "x");

        assertThat(ServerProcess.logFilesOf(dir.resolve("server.log")))
                .extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("server.log", "server.log.1.gz");
    }

    @Test
    void theLogMayListSeveralConnectorsInAnyOrder() throws Exception {
        Path log = dir.resolve("server.log");
        Files.writeString(log, "Tomcat started on ports 8443 (https), 41234 (http) with context path '/'\n");
        assertThat(ServerProcess.logShowsPort(log, 41234)).isTrue();
        assertThat(ServerProcess.logShowsPort(log, 8443)).isTrue();
        assertThat(ServerProcess.logShowsPort(log, 4123)).isFalse();
        assertThat(ServerProcess.logShowsPort(log, 443)).isFalse();

        Files.writeString(log, "Tomcat started on ports 41234 (http), 8443 (https)\n");
        assertThat(ServerProcess.logShowsPort(log, 41234)).isTrue();
        assertThat(ServerProcess.logShowsPort(log, 8443)).isTrue();
        assertThat(ServerProcess.logShowsPort(log, 9999)).isFalse();
    }

    @Test
    void aCommandThatNeverEndsIsAbandonedAtTheBoundAndKilled() {
        long started = System.nanoTime();

        String output = LoadTestMain.boundedOutput(List.of("sh", "-c", "echo partial; exec sleep 60"),
                Duration.ofMillis(500));

        assertThat(output).isEqualTo("unknown");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void aCommandThatEndsReturnsItsTrimmedOutput() {
        assertThat(LoadTestMain.boundedOutput(List.of("sh", "-c", "echo hello"), Duration.ofSeconds(10)))
                .isEqualTo("hello");
        assertThat(LoadTestMain.boundedOutput(List.of("sh", "-c", "exit 3"), Duration.ofSeconds(10)))
                .isEqualTo("unknown");
    }
}
