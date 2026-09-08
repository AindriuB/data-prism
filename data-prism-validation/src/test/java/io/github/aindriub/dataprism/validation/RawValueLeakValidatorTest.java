package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RawValueLeakValidatorTest {

    /** This validator compares against source values, so the emitted allowlist is never its input. */
    private static final Set<String> NONE = Set.of();

    private final RawValueLeakValidator validator = new RawValueLeakValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private final PrivacyContext context = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"),
            PseudonymisationVersion.HMAC_SHA256_V1);

    @Test
    @DisplayName("a scrubbed response passes")
    void passesScrubbedResponse() {
        ObjectNode response = mapper.createObjectNode();
        response.put("fullName", "Morgan Rossi (6H10)");
        response.put("state", "ACTIVE");

        assertThat(validator.validate(response, Set.of("Patrick Murphy"), NONE, context).valid()).isTrue();
    }

    @Test
    @DisplayName("a raw source value anywhere in the tree fails the response")
    void failsOnRawValue() {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("nested").putArray("aliases").add("Patrick Murphy");

        ValidationResult result = validator.validate(response, Set.of("Patrick Murphy"), NONE, context);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement()
                .satisfies(v -> {
                    assertThat(v.code()).isEqualTo("RAW_SOURCE_VALUE");
                    assertThat(v.path()).isEqualTo("$.nested.aliases[0]");
                });
    }

    @Test
    @DisplayName("a violation never records the value that triggered it")
    void violationCarriesNoValue() {
        ObjectNode response = mapper.createObjectNode();
        response.put("leaked", "patrick@example.invalid");

        ValidationResult result = validator.validate(
                response, Set.of("patrick@example.invalid"), NONE, context);

        // The violation is written to logs and audit. Putting the detected value
        // in it would leak exactly what the check just caught.
        assertThat(result.violations()).allSatisfy(v ->
                assertThat(v.toString()).doesNotContain("patrick@example.invalid"));
    }

    @Test
    @DisplayName("a leak spelled in another Unicode form is still caught")
    void catchesNormalisationVariants() {
        // The source stored the decomposed form; the response carries the
        // precomposed one. Byte equality would pass this and report the check as
        // successful, which is the worst outcome available to a last line of
        // defence.
        ObjectNode response = mapper.createObjectNode();
        response.put("name", "Seán Ó Súilleabháin");

        ValidationResult result = validator.validate(
                response, Set.of("Seán Ó Súilleabháin"), NONE, context);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement()
                .satisfies(v -> assertThat(v.code()).isEqualTo("RAW_SOURCE_VALUE"));
    }

    @Test
    @DisplayName("a leak hidden behind invisible marks is still caught")
    void catchesInvisibleCharacterVariants() {
        ObjectNode response = mapper.createObjectNode();
        response.put("name", "‎عبدالله‏");

        assertThat(validator.validate(response,
                Set.of("عبدالله"), NONE, context).valid()).isFalse();
    }

    @Test
    @DisplayName("normalisation does not make the validator over-eager")
    void doesNotOverMatch() {
        ObjectNode response = mapper.createObjectNode();
        response.put("name", "Sean Murphy");

        // "Sean" is not "Seán". Folding accents away would merge genuinely
        // different names and start refusing responses that are perfectly safe.
        assertThat(validator.validate(response, Set.of("Seán Murphy"), NONE, context).valid()).isTrue();
    }

    @Test
    @DisplayName("an empty prohibited set does not make the validator vacuous by accident")
    void emptyProhibitedPasses() {
        ObjectNode response = mapper.createObjectNode();
        response.put("anything", "at all");

        // Recorded deliberately: with nothing prohibited there is nothing to
        // find, so a caller passing an empty set gets no protection from this
        // validator. That is why the orchestrator derives the set from the
        // source rather than accepting one.
        assertThat(validator.validate(response, Set.of(), NONE, context).valid()).isTrue();
    }
}
