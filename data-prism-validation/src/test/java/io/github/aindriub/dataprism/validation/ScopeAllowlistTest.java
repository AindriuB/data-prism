package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scope-aware allowlist, and the proof that it is not simply switching
 * detection off.
 *
 * <p>Drives the real engine rather than a hand-built tree, because the property
 * under test is that the set the engine reports is the set the validator needs.
 * A test that assembled both by hand would pass whatever the engine did.
 *
 * <p>The pairing matters more than either test alone: a synthesised email
 * passes, the same response with a real one injected is refused, and the same
 * synthesised response with an empty allowlist is refused too. The first on its
 * own would pass just as happily if detection did nothing at all.
 */
class ScopeAllowlistTest {

    /**
     * Shaped like the pseudonymisation module's output. A synthetic email that
     * did not look like an email would be useless to a model, and would also
     * mean a bug emitting a real one went unnoticed by shape.
     */
    private static final SyntheticValueSource SYNTHETICS =
            (subject, namespace, context) ->
                    "person." + Integer.toHexString(subject.hashCode()) + "@example.invalid";

    private static final PrivacyProfile SYNTHESISING = new PrivacyProfile("DEFAULT",
            PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
            Map.of(DataClassification.CONTACT,
                    PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE)));

    private final JsonTreeScrubbingEngine engine = new JsonTreeScrubbingEngine(
            new DefaultFieldMetadataResolver(),
            new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", SYNTHESISING)),
            SYNTHETICS);

    private final SensitivePatternValidator validator = new SensitivePatternValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private final PrivacyContext context = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"),
            PseudonymisationVersion.HMAC_SHA256_V1);

    @LlmExposedModel
    record Contactable(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String email,
            @NonSensitive(reason = "Enumerated state, no free text")
            String state) {
    }

    private ScrubResult scrubbed() {
        return engine.scrub(new Contactable("s-1", "patrick@example.invalid", "ACTIVE"), context);
    }

    @Test
    @DisplayName("a synthesised email passes, because this scope emitted it")
    void synthesisedEmailPasses() {
        ScrubResult result = scrubbed();

        assertThat(validator.validate(result.tree(), Set.of(), result.emitted(), context).valid())
                .isTrue();
    }

    @Test
    @DisplayName("a real email injected into the same response is refused")
    void injectedEmailIsRefused() {
        ScrubResult result = scrubbed();
        ObjectNode tampered = result.tree();
        tampered.put("note", "reachable on nobody@example.com");

        ValidationResult validation =
                validator.validate(tampered, Set.of(), result.emitted(), context);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(SensitivePatternValidator.SENSITIVE_PATTERN);
            assertThat(violation.classification()).isEqualTo("CONTACT");
            assertThat(violation.path()).isEqualTo("$.note");
        });
    }

    @Test
    @DisplayName("without the allowlist the same synthesised response is refused")
    void allowlistIsWhatMakesTheSyntheticResponsePass() {
        // The mutation that proves the first test non-vacuous from the other
        // direction: detection is live on exactly the value the allowlist
        // exempts, so a synthesising profile deadlocks without it.
        ScrubResult result = scrubbed();

        assertThat(validator.validate(result.tree(), Set.of(), Set.of(), context).valid())
                .isFalse();
    }

    @Test
    @DisplayName("a violation names the classification and the path, never the value")
    void violationCarriesNoValue() {
        ScrubResult result = scrubbed();
        ObjectNode tampered = result.tree();
        tampered.put("note", "reachable on nobody@example.com");

        ValidationResult validation =
                validator.validate(tampered, Set.of(), result.emitted(), context);

        assertThat(validation.violations()).allSatisfy(violation ->
                assertThat(violation.toString()).doesNotContain("nobody@example.com"));
    }

    @Test
    @DisplayName("an emitted value spelled in another Unicode form is still allowed")
    void allowlistIsNormalisationAware() {
        ObjectNode response = mapper.createObjectNode();
        response.put("email", "seán@example.invalid");

        // The allowlist holds the decomposed spelling of the same address. Byte
        // equality would miss it and refuse the platform's own output over a
        // difference nobody can see.
        assertThat(validator.validate(response, Set.of(),
                Set.of("seán@example.invalid"), context).valid()).isTrue();
    }

    @Test
    @DisplayName("a response too large to scan is refused rather than passed")
    void incompleteScanIsRefused() {
        ObjectNode response = mapper.createObjectNode();
        response.put("padding", "x".repeat(200));

        var bounded = new SensitivePatternValidator(new SensitiveDataScanner(100));
        ValidationResult validation = bounded.validate(response, Set.of(), Set.of(), context);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).singleElement().satisfies(violation ->
                assertThat(violation.code()).isEqualTo(SensitivePatternValidator.SCAN_INCOMPLETE));
    }
}
