package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

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

    /** The declared fields of the exposed tree. */
    @LlmExposedModel
    record Open(@InternalIdentifier String id) {
    }

    @LlmExposedModel
    record WithEmail(@InternalIdentifier String id,
                     @InternalIdentifier String email) {
    }

    /**
     * Exposes an {@link ObjectNode} root with the declared fields of a record. A record source can no longer
     * carry undeclared keys (task 288, OD-288-1 (a)); a JsonNode root can, as a configured JSON source does.
     */
    static final class ExposedTree implements FieldMetadataResolver {
        private final DefaultFieldMetadataResolver delegate = new DefaultFieldMetadataResolver();
        private final Class<?> declared;

        ExposedTree(Class<?> declared) {
            this.declared = declared;
        }

        @Override
        public List<FieldMetadata> resolve(Class<?> type) {
            return delegate.resolve(type == ObjectNode.class ? declared : type);
        }

        @Override
        public boolean exposed(Class<?> type) {
            return type == ObjectNode.class || delegate.exposed(type);
        }
    }

    private static ObjectNode tree(Map<String, String> declared, String key, String value) {
        ObjectNode tree = io.github.aindriub.dataprism.core.engine.SourceTree.newObject();
        declared.forEach(tree::put);
        tree.put(key, value);
        return tree;
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static DefaultContextOrchestrator orchestrator(PrivacyProfile.UnclassifiedBehaviour behaviour,
                                                           String keyedValue, List<AuditEvent> events) {
        return orchestrator(behaviour, Open.class, tree(Map.of("id", "1"), KEY, keyedValue), events);
    }

    private static DefaultContextOrchestrator orchestrator(PrivacyProfile.UnclassifiedBehaviour behaviour,
                                                           Class<?> declared, ObjectNode payload,
                                                           List<AuditEvent> events) {
        Class<ObjectNode> type = ObjectNode.class;
        FieldMetadataResolver resolver = new ExposedTree(declared);
        var profile = new PrivacyProfile("DEFAULT", behaviour, Map.of(DataClassification.PII,
                PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        var scrubber = new JsonTreeScrubbingEngine(resolver,
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                (subject, namespace, ctx) -> "synthetic");
        DataSourceAdapter<ObjectNode> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "open-api";
            }

            @Override
            public Class<ObjectNode> responseType() {
                return type;
            }

            @Override
            public ObjectNode fetch(DataRequest request) {
                return payload;
            }
        };
        return new DefaultContextOrchestrator(List.of(adapter), scrubber, resolver,
                List.of(new RawValueLeakValidator(), new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(events::add, CLOCK, "test-1"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none(), OrchestrationOptions.defaults());
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
        var payload = tree(Map.of("id", "1", "email", "plain"), "email[07700900123]", "someone.else@example.com");
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
