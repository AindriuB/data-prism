package io.github.aindriub.dataprism.validation;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SensitivePatternValidatorRecogniserTest {

    private static final String SENTINEL = "SENTINEL-NAME-IN-PROSE";

    private static final FreeTextRecogniser SENTINEL_RECOGNISER =
            v -> v.contains(SENTINEL) ? Set.of(DataClassification.PII) : Set.of();

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrivacyContext context = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"),
            PseudonymisationVersion.HMAC_SHA256_V1);

    private ObjectNode note(String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("note", text);
        return node;
    }

    private ValidationResult run(List<FreeTextRecogniser> recognisers, ObjectNode response, Set<String> emitted) {
        return new SensitivePatternValidator(new SensitiveDataScanner(), recognisers)
                .validate(response, Set.of(), emitted, context);
    }

    @Test
    @DisplayName("a recognised leaf refuses, naming only the classification")
    void recognisedRefuses() {
        ValidationResult r = run(List.of(SENTINEL_RECOGNISER), note("met " + SENTINEL + " today"), Set.of());
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(SensitivePatternValidator.FREE_TEXT_RECOGNISED);
            assertThat(v.detectionMethod()).contains("PII").doesNotContain(SENTINEL);
        });
    }

    @Test
    @DisplayName("an unrecognised leaf passes, and no recogniser means the existing scan")
    void unrecognisedPasses() {
        assertThat(run(List.of(SENTINEL_RECOGNISER), note("nothing here"), Set.of()).valid()).isTrue();
        assertThat(run(List.of(), note(SENTINEL), Set.of()).valid()).isTrue();
    }

    @Test
    @DisplayName("an emitted leaf is not passed to recognisers")
    void emittedLeafExempt() {
        List<String> seen = new ArrayList<>();
        FreeTextRecogniser spy = v -> {
            seen.add(v);
            return Set.of();
        };
        assertThat(run(List.of(spy), note(SENTINEL), Set.of(SENTINEL)).valid()).isTrue();
        assertThat(seen).doesNotContain(SENTINEL);
    }

    @Test
    @DisplayName("recognisers run in list order")
    void listOrder() {
        List<String> order = new ArrayList<>();
        run(List.of(v -> {
            order.add("a");
            return Set.of();
        }, v -> {
            order.add("b");
            return Set.of();
        }), note("x"), Set.of());
        // Once for the property name and once for the value: both are payload text the scan visits.
        assertThat(order).containsExactly("a", "b", "a", "b");
    }

    @Test
    @DisplayName("a throwing recogniser refuses and nothing of the exception appears")
    void throwingRefuses() {
        FreeTextRecogniser boom = v -> {
            throw new IllegalStateException(SENTINEL);
        };
        ValidationResult r = run(List.of(boom), note("x"), Set.of());
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).isNotEmpty().allSatisfy(v ->
                assertThat(v.code()).isEqualTo(SensitivePatternValidator.FREE_TEXT_RECOGNISER_FAILED));
        assertThat(r.violations().toString()).doesNotContain(SENTINEL).doesNotContain("IllegalState");
    }

    @Test
    @DisplayName("a recogniser returning null is a failure")
    void nullRefuses() {
        ValidationResult r = run(List.of(v -> null), note("x"), Set.of());
        assertThat(r.violations()).isNotEmpty().allSatisfy(v ->
                assertThat(v.code()).isEqualTo(SensitivePatternValidator.FREE_TEXT_RECOGNISER_FAILED));
    }

    @Test
    @DisplayName("past the size budget the response is refused as too large to scan")
    void budgetShared() {
        var bounded = new SensitivePatternValidator(new SensitiveDataScanner(100), List.of(SENTINEL_RECOGNISER));
        ValidationResult r = bounded.validate(note("x".repeat(200)), Set.of(), Set.of(), context);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).extracting(Violation::code).contains(SensitivePatternValidator.SCAN_INCOMPLETE);
    }

    @Test
    @DisplayName("past the depth cap the response is refused as too large to scan")
    void depthShared() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode cur = root;
        for (int i = 0; i < 20; i++) {
            cur = cur.putObject("n");
        }
        cur.put("note", SENTINEL);
        ValidationResult r = run(List.of(SENTINEL_RECOGNISER), root, Set.of());
        assertThat(r.violations()).extracting(Violation::code).contains(SensitivePatternValidator.SCAN_INCOMPLETE);
    }
}
