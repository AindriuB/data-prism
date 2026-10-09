package io.github.aindriub.dataprism.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The status codes are a documented client contract (docs/tools.md): adding, removing or renaming one is a decision. */
class SourceStatusCodesTest {

    @Test
    @DisplayName("SourceOutcome.Status is exactly the seven documented codes, in the documented order")
    void statusSetIsThePublishedContract() throws IOException {
        List<String> documented;
        try (InputStream in = getClass().getResourceAsStream("/source-status-codes.txt")) {
            documented = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }

        assertThat(documented).hasSize(7);
        assertThat(Arrays.stream(SourceOutcome.Status.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(documented);
    }
}
