package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.ConsistencyFinding;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pipeline's own §89 metrics: what it emits for one allowed call and one refused one. */
class DefaultContextOrchestratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");

    private record Thing(String id, String value) {
    }

    /** A namespaced field, so two sources can genuinely agree before scrubbing. */
    @LlmExposedModel
    private record NamedThing(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private static DataSourceAdapter<Thing> answering(String name, Thing thing) {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                return thing;
            }
        };
    }

    private static DefaultContextOrchestrator orchestrator(LlmResponseValidator validator,
                                                            PrivacyMetrics metrics) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ObjectMapper mapper = new ObjectMapper();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(mapper.createObjectNode().put("value", "ok"), Set.of());

        return new DefaultContextOrchestrator(
                List.of(answering("thing-api", new Thing("1", "raw"))),
                scrubber, resolver, List.of(validator, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(event -> { }, CLOCK, "test-1"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), metrics),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                metrics);
    }

    private static DataSourceAdapter<NamedThing> answeringNamed(String name, NamedThing thing) {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class<NamedThing> responseType() {
                return NamedThing.class;
            }

            @Override
            public NamedThing fetch(DataRequest request) {
                return thing;
            }
        };
    }

    /**
     * Two sources that hold the same namespaced value for the same subject, so
     * real correlation produces a {@link ConsistencyFinding.Kind#CONSISTENT}
     * finding — the fixture both pinned-output tests below need.
     */
    private static DefaultContextOrchestrator orchestratorWithAgreeingSources(AuditRecorder audit) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ObjectMapper mapper = new ObjectMapper();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(mapper.createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator alwaysOk = (response, prohibited, emitted, ctx) -> ValidationResult.ok();

        return new DefaultContextOrchestrator(
                List.of(answeringNamed("source-a", new NamedThing("1", "Patrick Murphy")),
                        answeringNamed("source-b", new NamedThing("1", "Patrick Murphy"))),
                scrubber, resolver, List.of(alwaysOk, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
    }

    private static final class RecordingMetrics implements PrivacyMetrics {
        private final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);

        @Override
        public void increment(Metric metric) {
            counts.merge(metric, 1, Integer::sum);
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            counts.merge(metric, 1, Integer::sum);
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
        }

        int count(Metric metric) {
            return counts.getOrDefault(metric, 0);
        }
    }

    @Test
    @DisplayName("an allowed call counts one scrubbed record and nothing else")
    void allowedCallIncrementsTransformationsOnly() {
        RecordingMetrics metrics = new RecordingMetrics();
        LlmResponseValidator alwaysOk = (response, prohibited, emitted, ctx) -> ValidationResult.ok();

        orchestrator(alwaysOk, metrics).buildContext(
                ContextRequest.of("THING", "1"), context(), caller());

        assertThat(metrics.count(Metric.PRIVACY_TRANSFORMATIONS)).isEqualTo(1);
        assertThat(metrics.count(Metric.PRIVACY_VALIDATION_FAILURES)).isZero();
        assertThat(metrics.count(Metric.PRIVACY_FAILCLOSED)).isZero();
    }

    @Test
    @DisplayName("a refused call counts its violation and fails closed")
    void refusedCallIncrementsValidationFailuresAndFailClosed() {
        RecordingMetrics metrics = new RecordingMetrics();
        LlmResponseValidator alwaysFails = (response, prohibited, emitted, ctx) ->
                ValidationResult.failed(List.of(new Violation("value", "LEAKED", "test")));

        DefaultContextOrchestrator orchestrator = orchestrator(alwaysFails, metrics);

        assertThatThrownBy(() -> orchestrator.buildContext(
                ContextRequest.of("THING", "1"), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class);

        assertThat(metrics.count(Metric.PRIVACY_TRANSFORMATIONS)).isEqualTo(1);
        assertThat(metrics.count(Metric.PRIVACY_VALIDATION_FAILURES)).isEqualTo(1);
        assertThat(metrics.count(Metric.PRIVACY_FAILCLOSED)).isEqualTo(1);
    }

    @Test
    @DisplayName("the 8-argument constructor still refuses a value never in a classified field")
    void eightArgumentConstructorStillRunsThePatternScan() {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ObjectMapper mapper = new ObjectMapper();
        // Nothing here is drawn from a classified field, so no comparison
        // check could ever catch it — only the pattern scan can.
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(mapper.createObjectNode()
                        .put("note", "reachable on nobody@example.com"), Set.of());
        LlmResponseValidator alwaysOk = (response, prohibited, emitted, ctx) -> ValidationResult.ok();

        DefaultContextOrchestrator orchestrator = new DefaultContextOrchestrator(
                List.of(answering("thing-api", new Thing("1", "raw"))),
                scrubber, resolver, alwaysOk,
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(event -> { }, CLOCK, "test-1"),
                new SourceAliasing(new HmacValueTokenSource(KEYS)));

        assertThatThrownBy(() -> orchestrator.buildContext(
                ContextRequest.of("THING", "1"), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class);
    }

    @Test
    @DisplayName("get_entity_context's own output is unchanged: no agreement finding reaches "
            + "a ContextResponse built for the existing tool's request, even when two sources genuinely agree")
    void agreementFindingsDoNotReachTheExistingTool() {
        List<AuditEvent> audited = new ArrayList<>();
        AuditRecorder audit = new AuditRecorder(audited::add, CLOCK, "test-1");

        ContextResponse response = orchestratorWithAgreeingSources(audit)
                .buildContext(ContextRequest.of("THING", "1"), context(), caller());

        // Pinned: the fixture genuinely produces a CONSISTENT finding once asked
        // for (see agreementFindingsReachTheComparisonPath below) -- it is this
        // call, not the correlation service, that must not carry it through.
        assertThat(response.findings())
                .noneMatch(f -> f.kind() == ConsistencyFinding.Kind.CONSISTENT);
        assertThat(audited).singleElement()
                .satisfies(e -> assertThat(e.tool()).isEqualTo("get_entity_context"));
    }

    @Test
    @DisplayName("a comparison request keeps agreement findings and is audited under its own tool name")
    void agreementFindingsReachTheComparisonPath() {
        List<AuditEvent> audited = new ArrayList<>();
        AuditRecorder audit = new AuditRecorder(audited::add, CLOCK, "test-1");

        ContextResponse response = orchestratorWithAgreeingSources(audit).buildContext(
                ContextRequest.comparison("THING", "1", Set.of(), "compare_entity_sources"),
                context(), caller());

        assertThat(response.findings())
                .anyMatch(f -> f.kind() == ConsistencyFinding.Kind.CONSISTENT);
        assertThat(audited).singleElement()
                .satisfies(e -> assertThat(e.tool()).isEqualTo("compare_entity_sources"));
    }
}
