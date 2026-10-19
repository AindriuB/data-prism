package io.github.aindriub.dataprism.validation;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property names are scanned as well as values. Under a Java-built
 * PASS_THROUGH_UNSAFE profile a payload key reaches the response verbatim, so a
 * map keyed by email address would otherwise pass every check.
 *
 * <p>Every fixture is synthetic and checksum-invalid.
 */
class KeyShapeScanTest {

    private final SensitivePatternValidator patterns = new SensitivePatternValidator();
    private final RawValueLeakValidator raw = new RawValueLeakValidator();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final PrivacyContext context = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"),
            PseudonymisationVersion.HMAC_SHA256_V1);

    @ParameterizedTest(name = "a property name shaped like {0} is refused")
    @ValueSource(strings = {
            "IE00TEST99999999999999",
            "4000000000000000",
            "1234567A",
            "000-00-0000",
            "someone@example.invalid",
            "+44 7700 900123",
            "eyJhbGciOiJub25lIn0.eyJzdWIiOiJub2JvZHkifQ.bm90LWEtc2lnbmF0dXJl",
            "sk_test_abcdefghijklmnop"})
    void refusesKeyShape(String key) {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("balances").put(key, "ok");

        ValidationResult result = patterns.validate(response, Set.of(), Set.of(), context);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(SensitivePatternValidator.SENSITIVE_PATTERN);
            assertThat(v.path()).isEqualTo("$.balances.<undeclared>");
            assertThat(v.toString()).doesNotContain(key);
        });
    }

    @Test
    @DisplayName("a value under a refused key does not put the key back into its path")
    void subtreePathStaysRedacted() {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("m").putObject("someone@example.invalid").put("note", "other@example.invalid");

        ValidationResult result = patterns.validate(response, Set.of(), Set.of(), context);

        assertThat(result.violations()).hasSize(2)
                .allSatisfy(v -> assertThat(v.toString()).doesNotContain("example.invalid"));
    }

    @Test
    @DisplayName("a property name in the emitted set is not refused")
    void emittedKeyAllowed() {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("m").put("synthetic@example.invalid", "ok");

        assertThat(patterns.validate(response, Set.of(), Set.of("synthetic@example.invalid"), context).valid())
                .isTrue();
    }

    @Test
    @DisplayName("an ordinary property name is not refused")
    void ordinaryKeyAllowed() {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("m").put("<undeclared-1>", "ok").put("fullName", "x");

        assertThat(patterns.validate(response, Set.of(), Set.of(), context).valid()).isTrue();
    }

    @Test
    @DisplayName("a property name equal to a prohibited raw value is refused, with the name redacted")
    void rawValueAsKey() {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("m").put("Patrick Murphy", "ok");

        ValidationResult result = raw.validate(response, Set.of("Patrick Murphy"), Set.of(), context);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo("RAW_SOURCE_VALUE");
            assertThat(v.path()).isEqualTo("$.m.<undeclared>");
        });
    }

    @Test
    @DisplayName("the raw-value check ignores the emitted set for keys too")
    void rawKeyNotInEmittedSetExemption() {
        ObjectNode response = mapper.createObjectNode();
        response.put("Patrick Murphy", "ok");

        assertThat(raw.validate(response, Set.of("Patrick Murphy"), Set.of("Patrick Murphy"), context).valid())
                .isFalse();
    }
}
