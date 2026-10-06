package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payload key can itself be personal data (a map keyed by email address), so
 * no refusal path, exception message or audit field may repeat one (task 118).
 */
class UndeclaredKeyRefusalPathTest {

    /** Synthetic; the reserved domain keeps it from ever being a real address. */
    private static final String KEY = "zzUndeclaredKeyQx7@example.com";
    private static final String TOKEN = "zzUndeclaredKeyQx7";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");

    @LlmExposedModel
    record Open(@InternalIdentifier String id,
                @JsonAnyGetter Map<String, String> extra) {
    }

    @LlmExposedModel
    record WithEmail(@InternalIdentifier String id,
                     @InternalIdentifier String email,
                     @JsonAnyGetter Map<String, String> extra) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static DefaultContextOrchestrator orchestrator(PrivacyProfile.UnclassifiedBehaviour behaviour,
                                                           String keyedValue, List<AuditEvent> events) {
        return orchestrator(behaviour, Open.class, new Open("1", Map.of(KEY, keyedValue)), events);
    }

    private static <T> DefaultContextOrchestrator orchestrator(PrivacyProfile.UnclassifiedBehaviour behaviour,
                                                               Class<T> type, T payload,
                                                               List<AuditEvent> events) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        var profile = new PrivacyProfile("DEFAULT", behaviour, Map.of(DataClassification.PII,
                PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        var scrubber = new JsonTreeScrubbingEngine(resolver,
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                (subject, namespace, ctx) -> "synthetic");
        DataSourceAdapter<T> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "open-api";
            }

            @Override
            public Class<T> responseType() {
                return type;
            }

            @Override
            public T fetch(DataRequest request) {
                return payload;
            }
        };
        return new DefaultContextOrchestrator(List.of(adapter), scrubber, resolver,
                List.of(new RawValueLeakValidator(), new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(events::add, CLOCK, "test-1"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
    }

    private static AuditedRefusalException refusal(DefaultContextOrchestrator orchestrator) {
        var request = ContextRequest.of("THING", "subject-1");
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                orchestrator.buildContext(request, context(),
                        new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of())));
        assertThat(thrown).isInstanceOf(AuditedRefusalException.class);
        return (AuditedRefusalException) thrown;
    }

    private static void assertNothingLeaks(AuditedRefusalException refused, List<AuditEvent> events) {
        assertThat(refused.path()).doesNotContain(TOKEN);
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.policyDecision()).isEqualTo("DENY:" + refused.code());
        // The record's toString names every component, so this checks each field of the event.
        assertThat(event.toString()).doesNotContain(TOKEN);
        assertThat(event.fieldDispositions().keySet()).noneMatch(k -> k.contains(TOKEN));
    }

    @Test
    @DisplayName("UNKNOWN_FIELD refuses with the undeclared placeholder, never the key")
    void unknownFieldRefusal() {
        var events = new ArrayList<AuditEvent>();
        var refused = refusal(orchestrator(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST, "plain", events));

        assertThat(refused.code()).isEqualTo("UNKNOWN_FIELD");
        assertThat(refused.path()).isEqualTo("$.<undeclared>");
        assertThat(events.get(0).policyDecision()).isEqualTo("DENY:UNKNOWN_FIELD");
        assertNothingLeaks(refused, events);
    }

    @Test
    @DisplayName("VALIDATION_FAILED under a pass-through profile renders the undeclared key as the placeholder")
    void validationFailedRefusal() {
        var events = new ArrayList<AuditEvent>();
        var refused = refusal(orchestrator(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE,
                "someone.else@example.com", events));

        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(refused.path()).isEqualTo("$.<undeclared>");
        assertThat(events.get(0).policyDecision()).isEqualTo("DENY:VALIDATION_FAILED");
        assertNothingLeaks(refused, events);
    }

    @Test
    @DisplayName("VALIDATION_FAILED never carries digits from a bracketed payload key after a declared name")
    void bracketedDigitKeyAfterDeclaredName() {
        var events = new ArrayList<AuditEvent>();
        var payload = new WithEmail("1", "plain", Map.of("email[07700900123]", "someone.else@example.com"));
        var refused = refusal(orchestrator(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE,
                WithEmail.class, payload, events));

        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(refused.path()).isEqualTo("$.email[*]");
        assertThat(refused.path()).doesNotContain("07700900123");
        assertThat(refused.getMessage()).doesNotContain("07700900123");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toString()).doesNotContain("07700900123");
    }
}
