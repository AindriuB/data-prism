package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The id travels as a per-request attribute; this module never reaches for ambient state. */
class NoAmbientCorrelationSourceTest {

    @Test
    @DisplayName("main sources never mention ThreadLocal, MDC or RequestContextHolder")
    void noAmbientState() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(main).isDirectory();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(main)) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String text = Files.readString(p);
                    return text.contains("ThreadLocal") || text.contains("MDC")
                            || text.contains("RequestContextHolder");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(Path::toString).toList();
        }
        assertThat(offenders).isEmpty();
    }
}
