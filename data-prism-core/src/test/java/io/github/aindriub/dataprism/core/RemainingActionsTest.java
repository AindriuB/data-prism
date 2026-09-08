package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.policy.GeneralizationRule;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HASH, TOKENIZE and GENERALIZE: the actions S0 through S2 left refusing. */
class RemainingActionsTest {

    /**
     * Value-keyed and deterministic, and deliberately not echoing the value: a
     * stub that embedded it would make "no raw value survives" fail for a reason
     * that has nothing to do with the engine.
     */
    private static final ValueTokenSource TOKENS = new ValueTokenSource() {
        @Override
        public String hash(String value, PrivacyNamespace namespace, PrivacyContext context) {
            return "hash:" + Integer.toHexString(value.hashCode());
        }

        @Override
        public String token(String value, PrivacyNamespace namespace, PrivacyContext context) {
            return "token:" + Integer.toHexString(value.hashCode());
        }
    };

    private static final SyntheticValueSource SYNTHETICS =
            (subject, namespace, context) -> "synthetic";

    @LlmExposedModel
    record Account(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.BANKING,
                    namespace = PrivacyNamespace.BANK_ACCOUNT,
                    suggestedAction = PrivacyAction.HASH)
            String iban,
            @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                    namespace = PrivacyNamespace.ACCOUNT_IDENTITY,
                    suggestedAction = PrivacyAction.TOKENIZE)
            String accountRef,
            @SensitiveData(classifications = DataClassification.FINANCIAL,
                    namespace = PrivacyNamespace.FINANCIAL_VALUE,
                    suggestedAction = PrivacyAction.GENERALIZE)
            BigDecimal balance,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_IDENTITY,
                    suggestedAction = PrivacyAction.GENERALIZE)
            String dateOfBirth) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static PrivacyProfile profile() {
        return new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(),
                Map.of(PrivacyNamespace.FINANCIAL_VALUE,
                        GeneralizationRule.bands(
                                List.of(new BigDecimal("0"), new BigDecimal("1000"),
                                        new BigDecimal("10000")), "EUR"),
                        PrivacyNamespace.PERSON_IDENTITY,
                        GeneralizationRule.dates(GeneralizationRule.Precision.YEAR)));
    }

    private static JsonTreeScrubbingEngine engine(ValueTokenSource tokens) {
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile())), SYNTHETICS, tokens);
    }

    /**
     * Shaped like an IBAN and deliberately not one: the mod-97 remainder is 69
     * rather than 1, and the bank identifier is invented. A valid IBAN committed
     * here would be the leak this project exists to prevent.
     */
    private static final String INVALID_IBAN = "IE00TEST99999999999999";

    @Test
    @DisplayName("HASH and TOKENIZE derive from the value, so equal values stay equal")
    void valueKeyedActions() {
        ObjectNode first = engine(TOKENS).scrub(new Account("s-1", INVALID_IBAN,
                "ACC-77", new BigDecimal("500"), "1980-04-17"), context()).tree();
        ObjectNode second = engine(TOKENS).scrub(new Account("s-2", INVALID_IBAN,
                "ACC-88", new BigDecimal("500"), "1980-04-17"), context()).tree();

        // Two different subjects, one shared account: the join survives, which is
        // the point of a value-keyed action and also its disclosure.
        assertThat(second.get("iban")).isEqualTo(first.get("iban"));
        assertThat(second.get("accountRef")).isNotEqualTo(first.get("accountRef"));
        assertThat(first.toString()).doesNotContain(INVALID_IBAN);
    }

    @Test
    @DisplayName("HASH and TOKENIZE refuse when no token source is configured")
    void refuseWithoutATokenSource() {
        // Degrading to pass-through or to a plain digest would both be worse than
        // saying the deployment has not chosen.
        assertThatThrownBy(() -> engine(ValueTokenSource.unavailable()).scrub(
                new Account("s-1", "IE29", "ACC-1", new BigDecimal("1"), "1980-04-17"), context()))
                .isInstanceOf(PrivacyRefusedException.class)
                .extracting(e -> ((PrivacyRefusedException) e).code())
                .isEqualTo("NO_TOKEN_SOURCE");
    }

    @Test
    @DisplayName("GENERALIZE replaces a number with its band and a date with its year")
    void generalises() {
        ObjectNode out = engine(TOKENS).scrub(new Account("s-1", "IE29", "ACC-1",
                new BigDecimal("4200.55"), "1980-04-17"), context()).tree();

        assertThat(out.get("balance").asText()).isEqualTo("EUR 1000–10000");
        assertThat(out.get("dateOfBirth").asText()).isEqualTo("1980");
        assertThat(out.toString()).doesNotContain("4200").doesNotContain("04-17");
    }

    @Test
    @DisplayName("values outside the configured bands are open-ended, not clamped")
    void bandsAreOpenEnded() {
        assertThat(engine(TOKENS).scrub(new Account("s-1", "IE29", "ACC-1",
                new BigDecimal("-5"), "1980-04-17"), context()).tree().get("balance").asText())
                .isEqualTo("< EUR 0");

        // A closed top band would either be unbounded in effect or would have to
        // name the largest value seen, which is itself a disclosure.
        assertThat(engine(TOKENS).scrub(new Account("s-1", "IE29", "ACC-1",
                new BigDecimal("99999"), "1980-04-17"), context()).tree().get("balance").asText())
                .isEqualTo(">= EUR 10000");
    }

    @Test
    @DisplayName("GENERALIZE with no rule for the namespace fails rather than approximating")
    void generaliseWithoutARuleFails() {
        var bare = new PrivacyProfile("DEFAULT",
                PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST, Map.of());
        var engine = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", bare)), SYNTHETICS, TOKENS);

        assertThatThrownBy(() -> engine.scrub(new Account("s-1", "IE29", "ACC-1",
                new BigDecimal("1"), "1980-04-17"), context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no rule");
    }

    @Test
    @DisplayName("a value that does not fit its rule is refused, not silently mangled")
    void typeMismatchIsRefused() {
        @LlmExposedModel
        record Odd(@InternalIdentifier String subjectRef,
                   @SensitiveData(classifications = DataClassification.FINANCIAL,
                           namespace = PrivacyNamespace.FINANCIAL_VALUE,
                           suggestedAction = PrivacyAction.GENERALIZE)
                   String balance) {
        }

        assertThatThrownBy(() -> engine(TOKENS).scrub(new Odd("s-1", "not a number"), context()))
                .isInstanceOf(PrivacyRefusedException.class)
                .extracting(e -> ((PrivacyRefusedException) e).code())
                .isEqualTo("GENERALIZE_TYPE_MISMATCH");
    }

    @Test
    @DisplayName("band bounds must ascend, checked when the rule is built")
    void boundsMustAscend() {
        // Out-of-order bounds would produce overlapping or inverted bands and the
        // output would still look plausible.
        assertThatThrownBy(() -> GeneralizationRule.bands(
                List.of(new BigDecimal("100"), new BigDecimal("10")), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must ascend");

        assertThatThrownBy(() -> GeneralizationRule.bands(List.of(new BigDecimal("1")), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two bounds");
    }
}
