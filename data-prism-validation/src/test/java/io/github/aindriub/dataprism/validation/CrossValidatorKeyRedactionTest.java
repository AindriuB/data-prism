package io.github.aindriub.dataprism.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.RefusalPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each leak validator redacts, in its own paths, every property name either validator flags, so the
 * guarantee holds when a validator is called directly and does not rest on {@code RefusalPaths}.
 */
class CrossValidatorKeyRedactionTest {

    private static final String RAW_VALUE = "Patrick Murphy";
    private static final Set<String> PROHIBITED = Set.of(RAW_VALUE);

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final PrivacyContext context = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"),
            PseudonymisationVersion.HMAC_SHA256_V1);

    @Test
    @DisplayName("RawValueLeakValidator redacts a key only the pattern scan flags")
    void rawValueValidatorRedactsAPatternShapedKey() throws Exception {
        JsonNode response = mapper.readTree("{\"ann@example.invalid\":{\"note\":\"" + RAW_VALUE + "\"}}");

        ValidationResult result = new RawValueLeakValidator().validate(response, PROHIBITED, Set.of(), context);

        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo("RAW_SOURCE_VALUE");
            assertThat(v.path()).isEqualTo("$.<undeclared>.note");
        });
        // The orchestrator's own redaction, still in place, gives the same refusal path.
        assertThat(RefusalPaths.redact(result.violations().get(0).path(), Set.of()))
                .isEqualTo(RefusalPaths.redact("$.ann@example.invalid.note", Set.of()));
    }

    @Test
    @DisplayName("SensitivePatternValidator redacts a key only the raw-value check flags")
    void patternValidatorRedactsARawValueKey() throws Exception {
        JsonNode response = mapper.readTree("{\"" + RAW_VALUE + "\":{\"mail\":\"ann@example.invalid\"}}");

        ValidationResult result = new SensitivePatternValidator().validate(response, PROHIBITED, Set.of(), context);

        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo("SENSITIVE_PATTERN");
            assertThat(v.path()).isEqualTo("$.<undeclared>.mail");
        });
        assertThat(RefusalPaths.redact(result.violations().get(0).path(), Set.of()))
                .isEqualTo(RefusalPaths.redact("$.Patrick Murphy.mail", Set.of()));
    }

    @Test
    @DisplayName("a key that neither validator flags is left in the path")
    void unflaggedKeysAreKept() throws Exception {
        JsonNode raw = mapper.readTree("{\"notes\":{\"note\":\"" + RAW_VALUE + "\"}}");
        JsonNode pattern = mapper.readTree("{\"notes\":{\"mail\":\"ann@example.invalid\"}}");

        assertThat(new RawValueLeakValidator().validate(raw, PROHIBITED, Set.of(), context)
                .violations().get(0).path()).isEqualTo("$.notes.note");
        assertThat(new SensitivePatternValidator().validate(pattern, PROHIBITED, Set.of(), context)
                .violations().get(0).path()).isEqualTo("$.notes.mail");
    }
}
