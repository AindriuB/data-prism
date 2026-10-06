package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile.UnclassifiedBehaviour;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A payload key can be personal data, so a profile that admits undeclared
 * properties must not write the key into the tree the model reads (task 124).
 * Disposition keys stay as task 118 left them.
 */
class UndeclaredPropertyNameTest {

    /** Synthetic; reserved domain. */
    private static final String ZETA = "zeta@example.com";
    private static final String ALPHA = "alpha@example.com";

    @SensitiveObject
    record Child(@NonSensitive(reason = "enumerated") String state,
                 @JsonAnyGetter Map<String, Object> extra) {
    }

    @LlmExposedModel
    record Open(@InternalIdentifier String subjectRef,
                @NonSensitive(reason = "enumerated") String status,
                @NonSensitive(reason = "reviewed separately") Child child,
                @NonSensitive(reason = "carrier for undeclared properties") @JsonAnyGetter
                Map<String, Object> extra) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static ProfilePrivacyPolicyResolver policies(UnclassifiedBehaviour behaviour) {
        var profile = new PrivacyProfile("DEFAULT", behaviour,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        return new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile));
    }

    private static JsonTreeScrubbingEngine engine(UnclassifiedBehaviour behaviour) {
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(), policies(behaviour),
                (subject, namespace, ctx) -> "synthetic");
    }

    private static Map<String, Object> ordered(String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, "v-" + k.length());
        }
        return m;
    }

    private static List<String> names(ObjectNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    @DisplayName("REDACT_AND_WARN renames undeclared properties alphabetically, in place, and restarts per object")
    void renamesAlphabetically() {
        var source = new Open("s-1", "ACTIVE",
                new Child("OPEN", ordered("yy@example.com", "bb@example.com")),
                ordered(ZETA, ALPHA, "mid"));

        ObjectNode out = engine(UnclassifiedBehaviour.REDACT_AND_WARN).scrub(source, context()).tree();

        assertThat(names(out)).containsExactly("status", "child", "<undeclared-3>", "<undeclared-1>", "<undeclared-2>");
        assertThat(out.get("<undeclared-3>").asText()).isEqualTo("[REDACTED]");
        assertThat(out.get("<undeclared-1>").asText()).isEqualTo("[REDACTED]");
        assertThat(out.get("<undeclared-2>").asText()).isEqualTo("[REDACTED]");
        assertThat(out.get("status").asText()).isEqualTo("ACTIVE");
        ObjectNode child = (ObjectNode) out.get("child");
        assertThat(names(child)).containsExactly("state", "<undeclared-2>", "<undeclared-1>");
        assertThat(out.toString()).doesNotContain("example.com").doesNotContain("mid");
    }

    @Test
    @DisplayName("the same input in a different insertion order gets the same name-to-placeholder assignment")
    void assignmentIsOrderIndependent() {
        var forward = new Open("s-1", "ACTIVE", null, ordered(ZETA, ALPHA, "mid"));
        var reverse = new Open("s-1", "ACTIVE", null, ordered("mid", ALPHA, ZETA));
        var engine = engine(UnclassifiedBehaviour.REDACT_AND_WARN);

        // Make the values distinguishable so the assignment is visible: pass-through shows it.
        var tagged = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                policies(UnclassifiedBehaviour.PASS_THROUGH_UNSAFE), (s, n, c) -> "synthetic");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(ZETA, "z");
        a.put(ALPHA, "a");
        a.put("mid", "m");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("mid", "m");
        b.put(ALPHA, "a");
        b.put(ZETA, "z");
        ObjectNode rawA = tagged.scrub(new Open("s", "x", null, a), context()).tree();
        ObjectNode rawB = tagged.scrub(new Open("s", "x", null, b), context()).tree();
        assertThat(rawA.get(ALPHA).asText()).isEqualTo("a");
        assertThat(rawB.get(ZETA).asText()).isEqualTo("z");

        ObjectNode outA = engine.scrub(forward, context()).tree();
        ObjectNode outB = engine.scrub(reverse, context()).tree();
        assertThat(outA.get("<undeclared-1>")).isNotNull();
        assertThat(outA.get("<undeclared-3>")).isNotNull();
        assertThat(outB.get("<undeclared-1>")).isNotNull();
        assertThat(outB.get("<undeclared-3>")).isNotNull();
        assertThat(names(outA)).containsExactlyInAnyOrderElementsOf(names(outB));
    }

    /** Declares a property literally named like the first placeholder. */
    @Test
    @DisplayName("a placeholder never overwrites a declared property of the same name")
    void placeholderSkipsDeclaredName() {
        record Mixed(String id, @JsonAnyGetter Map<String, Object> extra) {
        }
        var resolver = new FieldMetadataResolver() {
            @Override
            public List<FieldMetadata> resolve(Class<?> type) {
                return List.of(
                        new FieldMetadata("id", true, FieldMetadata.SELF, List.of(), PrivacyNamespace.NONE,
                                null, "", null, String.class, null),
                        new FieldMetadata("<undeclared-1>", false, null, List.of(), PrivacyNamespace.NONE,
                                null, "", "reviewed name that happens to look like a placeholder",
                                String.class, null));
            }

            @Override
            public boolean exposed(Class<?> type) {
                return type == Mixed.class;
            }
        };
        var engine = new JsonTreeScrubbingEngine(resolver, policies(UnclassifiedBehaviour.REDACT_AND_WARN),
                (s, n, c) -> "synthetic");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("<undeclared-1>", "declared-value");
        extra.put("a@example.com", "x");
        extra.put("b@example.com", "y");

        ObjectNode out = engine.scrub(new Mixed("1", extra), context()).tree();

        assertThat(out.get("<undeclared-1>").asText()).isEqualTo("declared-value");
        assertThat(out.get("<undeclared-2>").asText()).isEqualTo("[REDACTED]");
        assertThat(out.get("<undeclared-3>").asText()).isEqualTo("[REDACTED]");
        assertThat(out.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("DROP_AND_WARN emits no property, and no placeholder, for an undeclared key")
    void dropEmitsNothing() {
        var source = new Open("s-1", "ACTIVE", null, ordered(ZETA, ALPHA));

        ObjectNode out = engine(UnclassifiedBehaviour.DROP_AND_WARN).scrub(source, context()).tree();

        assertThat(names(out)).containsExactly("status", "child");
        assertThat(out.toString()).doesNotContain("undeclared").doesNotContain("example.com");
    }

    @Test
    @DisplayName("PASS_THROUGH_UNSAFE keeps the raw undeclared key (documented behaviour)")
    void passThroughKeepsKey() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put(ZETA, "z");
        var source = new Open("s-1", "ACTIVE", null, extra);

        ObjectNode out = engine(UnclassifiedBehaviour.PASS_THROUGH_UNSAFE).scrub(source, context()).tree();

        assertThat(names(out)).containsExactly("status", "child", ZETA);
        assertThat(out.get(ZETA).asText()).isEqualTo("z");
    }

    @Test
    @DisplayName("FAIL_REQUEST still refuses with UNKNOWN_FIELD")
    void failRequestUnchanged() {
        var source = new Open("s-1", "ACTIVE", null, ordered(ZETA));

        assertThatThrownBy(() -> engine(UnclassifiedBehaviour.FAIL_REQUEST).scrub(source, context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("UNKNOWN_FIELD"));
    }

    @Test
    @DisplayName("dispositions keep the single <undeclared> segment, never <undeclared-N>")
    void dispositionsUnchanged() {
        var source = new Open("s-1", "ACTIVE",
                new Child("OPEN", ordered("yy@example.com", "bb@example.com")),
                ordered(ZETA, ALPHA, "mid"));

        ScrubResult result = engine(UnclassifiedBehaviour.REDACT_AND_WARN).scrub(source, context());

        assertThat(result.dispositions()).containsEntry("/<undeclared>", PrivacyAction.REDACT)
                .containsEntry("/child/<undeclared>", PrivacyAction.REDACT);
        assertThat(result.dispositions().keySet()).noneMatch(k -> k.matches(".*<undeclared-\\d+>.*"));
    }
}
