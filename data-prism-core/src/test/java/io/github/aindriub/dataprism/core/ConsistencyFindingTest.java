package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConsistencyFinding#disagreement()} for every {@link ConsistencyFinding.Kind}, including
 * {@link ConsistencyFinding.Kind#CONSISTENT} — the agreement state task 42 adds.
 */
class ConsistencyFindingTest {

    private static ConsistencyFinding finding(ConsistencyFinding.Kind kind) {
        return new ConsistencyFinding("FIELD", PrivacyNamespace.PERSON_NAME, kind,
                List.of(List.of("a", "b")), 1, "detail");
    }

    @Test
    @DisplayName("INCONSISTENT, FORMATTING_ONLY, ABBREVIATION and MISSING_IN_SOME_SOURCES are disagreements")
    void ordinaryKindsAreDisagreements() {
        assertThat(finding(ConsistencyFinding.Kind.INCONSISTENT).disagreement()).isTrue();
        assertThat(finding(ConsistencyFinding.Kind.FORMATTING_ONLY).disagreement()).isTrue();
        assertThat(finding(ConsistencyFinding.Kind.ABBREVIATION).disagreement()).isTrue();
        assertThat(finding(ConsistencyFinding.Kind.MISSING_IN_SOME_SOURCES).disagreement()).isTrue();
    }

    @Test
    @DisplayName("SUSPECTED_INSTRUCTION_CONTENT is not a disagreement")
    void suspectedInstructionContentIsNotADisagreement() {
        assertThat(finding(ConsistencyFinding.Kind.SUSPECTED_INSTRUCTION_CONTENT).disagreement()).isFalse();
    }

    @Test
    @DisplayName("CONSISTENT is not a disagreement: it is the state that removes the ambiguity §42 exists for")
    void consistentIsNotADisagreement() {
        assertThat(finding(ConsistencyFinding.Kind.CONSISTENT).disagreement()).isFalse();
    }

    @Test
    @DisplayName("no finding kind carries a value: agreementGroups and detail are all this finding exposes")
    void noFindingCarriesAValue() {
        ConsistencyFinding f = finding(ConsistencyFinding.Kind.CONSISTENT);
        assertThat(f.toString()).doesNotContain("Patrick").doesNotContain("Murphy");
    }
}
