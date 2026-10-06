package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A payload key can be personal data (a map keyed by email address), and the
 * engine must never write one into a refusal path or message (task 118).
 */
class UndeclaredKeyRefusalTest {

    /** Synthetic; reserved domain. */
    private static final String KEY = "zzUndeclaredKeyQx7@example.com";
    private static final String TOKEN = "zzUndeclaredKeyQx7";

    @LlmExposedModel
    record Open(@InternalIdentifier String subjectRef,
                @JsonAnyGetter Map<String, Object> extra) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine(PrivacyProfile.UnclassifiedBehaviour behaviour) {
        var profile = new PrivacyProfile("DEFAULT", behaviour,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                (subject, namespace, ctx) -> "synthetic");
    }

    @Test
    @DisplayName("UNKNOWN_FIELD names the placeholder, not the payload key")
    void unknownField() {
        var source = new Open("s-1", Map.of(KEY, "v"));

        assertThatThrownBy(() -> engine(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST)
                .scrub(source, context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNKNOWN_FIELD");
                    assertThat(e.path()).isEqualTo("$.<undeclared>");
                    assertThat(e.getMessage()).doesNotContain(TOKEN).contains("UNKNOWN_FIELD");
                });
    }

    @Test
    @DisplayName("UNCLASSIFIED_STRUCTURE under an undeclared key names the placeholder, not the key")
    void unclassifiedStructure() {
        // A resolver that lets the undeclared property through and then refuses the
        // structure beneath it: the only way a payload key reaches this code.
        AtomicInteger undeclaredCalls = new AtomicInteger();
        var resolver = (io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver) (field, ctx) -> {
            if (field.declared()) {
                return new EffectivePrivacyPolicy(PrivacyAction.REMOVE, field.namespace(), true, "DEFAULT",
                        EffectivePrivacyPolicy.Decided.IDENTIFIER);
            }
            return undeclaredCalls.incrementAndGet() == 1
                    ? new EffectivePrivacyPolicy(PrivacyAction.PASS_THROUGH, field.namespace(), true, "DEFAULT",
                            EffectivePrivacyPolicy.Decided.UNCLASSIFIED)
                    : EffectivePrivacyPolicy.refuse("DEFAULT");
        };
        var engine = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(), resolver,
                (subject, namespace, ctx) -> "synthetic");
        var source = new Open("s-1", Map.of(KEY, Map.of("inner", "v")));

        assertThatThrownBy(() -> engine.scrub(source, context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNCLASSIFIED_STRUCTURE");
                    assertThat(e.path()).isEqualTo("$.<undeclared>");
                    assertThat(e.getMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    @DisplayName("RefusalPaths keeps declared segments and indices, and stops at the first undeclared one")
    void refusalPaths() {
        var declared = java.util.Set.of("/a", "/a/*", "/a/*/b", "/c");

        assertThat(RefusalPaths.redact("$", declared)).isEqualTo("$");
        assertThat(RefusalPaths.redact("$.a[2].b", declared)).isEqualTo("$.a[*].b");
        assertThat(RefusalPaths.redact("$.c", declared)).isEqualTo("$.c");
        assertThat(RefusalPaths.redact("$.a[0]." + KEY, declared)).isEqualTo("$.a[*].<undeclared>");
        assertThat(RefusalPaths.redact("$." + KEY + ".b", declared)).isEqualTo("$.<undeclared>");
        assertThat(RefusalPaths.redact("$.a[x" + TOKEN + "]", declared)).isEqualTo("$.a.<undeclared>");
        assertThat(RefusalPaths.redact("$.a" + TOKEN, declared)).isEqualTo("$.<undeclared>");
        assertThat(RefusalPaths.redact("no-dollar-" + TOKEN, declared)).isEqualTo("<undeclared>");
        assertThat(RefusalPaths.redact(null, declared)).isEqualTo("<undeclared>");
    }

    @Test
    @DisplayName("RefusalPaths never emits index digits: a bracketed-digit key after a declared name and a real index both render [*]")
    void refusalPathsCollapseIndices() {
        var declared = java.util.Set.of("/a", "/a/*", "/a/*/b", "/c", "/email");

        assertThat(RefusalPaths.redact("$.email[07700900123]", declared)).isEqualTo("$.email[*]");
        assertThat(RefusalPaths.redact("$.email[07700900123][5]", declared)).isEqualTo("$.email[*][*]");
        assertThat(RefusalPaths.redact("$.c[12]", declared)).isEqualTo("$.c[*]");
        assertThat(RefusalPaths.redact("$.a[3].b", declared)).isEqualTo("$.a[*].b");
    }

    @Test
    @DisplayName("FieldMetadata.undeclared never stores the payload key; unannotated keeps the declared name")
    void metadataNames() {
        assertThat(FieldMetadata.undeclared(KEY).fieldName()).isEqualTo("<undeclared>");
        assertThat(FieldMetadata.unannotated("note").fieldName()).isEqualTo("note");
    }
}
