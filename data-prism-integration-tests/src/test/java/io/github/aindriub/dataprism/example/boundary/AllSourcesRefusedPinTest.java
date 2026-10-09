package io.github.aindriub.dataprism.example.boundary;

import io.github.aindriub.dataprism.example.boundary.PerSourceHarness.Call;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.aindriub.dataprism.example.boundary.PerSourceHarness.call;
import static io.github.aindriub.dataprism.example.boundary.PerSourceHarness.empty;
import static io.github.aindriub.dataprism.example.boundary.PerSourceHarness.scrubRefusing;
import static io.github.aindriub.dataprism.example.boundary.PerSourceHarness.validationRefusing;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 190 pins: when no source is left to answer, the whole call is refused with the first refused
 * source's code and audited as {@code DENY:<code>}. These were written and run against the behaviour
 * before per-source refusal existed, and must keep passing unchanged.
 */
class AllSourcesRefusedPinTest {

    private static void assertRefused(Call call, String code) {
        assertThat(call.result().isError()).isTrue();
        assertThat(call.text()).startsWith("refused: " + code);
        assertThat(call.event().policyDecision()).isEqualTo("DENY:" + code);
        assertThat(call.text()).doesNotContain(PerSourceHarness.BAD_NAME).doesNotContain(PerSourceHarness.STRAY_VALUE).doesNotContain(PerSourceHarness.STRAY_KEY)
                .doesNotContain(PerSourceHarness.NOTE_VALUE);
    }

    @Test
    @DisplayName("one source that refuses in scrub refuses the call")
    void singleScrubRefusal() {
        assertRefused(call(List.of(scrubRefusing("bad-one"))), "UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("every source refusing in scrub refuses the call")
    void everySourceScrubRefusal() {
        assertRefused(call(List.of(scrubRefusing("bad-one"), scrubRefusing("bad-two"))), "UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("one source that refuses in validation refuses the call")
    void singleValidationRefusal() {
        assertRefused(call(List.of(validationRefusing("bad-one"))), "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("every source refusing in validation refuses the call")
    void everySourceValidationRefusal() {
        assertRefused(call(List.of(validationRefusing("bad-one"), validationRefusing("bad-two"))),
                "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a scrub-refusing source with every other source empty refuses the call")
    void scrubRefusalWithEmptyNeighbours() {
        assertRefused(call(List.of(empty("none-one"), scrubRefusing("bad-one"), empty("none-two"))),
                "UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("a validation-refusing source with every other source empty refuses the call")
    void validationRefusalWithEmptyNeighbours() {
        assertRefused(call(List.of(empty("none-one"), validationRefusing("bad-one"), empty("none-two"))),
                "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("scrub and validation refusals together: the scrub code is the one reported")
    void mixedRefusalsReportTheScrubCode() {
        assertRefused(call(List.of(validationRefusing("bad-one"), scrubRefusing("bad-two"))), "UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("no source answering at all is NO_SOURCE_DATA")
    void nothingAnswered() {
        assertRefused(call(List.of(empty("none-one"), empty("none-two"))), "NO_SOURCE_DATA");
    }
}
