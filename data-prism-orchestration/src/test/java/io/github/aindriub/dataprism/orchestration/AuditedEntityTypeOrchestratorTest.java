package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.InvestigationContext;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The audit record holds the audited entity type; the response and every source request keep the raw one. */
class AuditedEntityTypeOrchestratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-150-test-key-not-for-any-real-data-32b");

    private record Thing(String id, String value) {
    }

    private final List<AuditEvent> audited = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sourceSaw = Collections.synchronizedList(new ArrayList<>());

    private DefaultContextOrchestrator orchestrator() {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ObjectMapper mapper = new ObjectMapper();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(mapper.createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        DataSourceAdapter<Thing> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "source-a";
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                sourceSaw.add(request.entityType());
                return new Thing("1", "raw");
            }
        };
        return new DefaultContextOrchestrator(
                List.of(adapter), scrubber, resolver, List.of(ok, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(audited::add, CLOCK, "test-150"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    @Test
    @DisplayName("a request built without an audited entity type audits the sentinel: ACC-1 is audited as UNREGISTERED")
    void requestWithoutAuditedTypeAuditsTheSentinel() {
        ContextResponse response = orchestrator().buildContext(
                ContextRequest.of("ACC-1", "1"), context(), caller());

        assertThat(audited).singleElement().satisfies(e ->
                assertThat(e.entityType()).isEqualTo(AuditedEntityTypes.UNREGISTERED));
        assertThat(response.entityType()).isEqualTo("ACC-1");
        assertThat(sourceSaw).containsExactly("ACC-1");
    }

    @Test
    @DisplayName("an explicit auditedEntityType is what is audited; response and source keep the raw value")
    void explicitAuditedValueIsAudited() {
        ContextRequest request = new ContextRequest("ACC-1", "1", Set.of(), ContextRequest.DEFAULT_TOOL_NAME,
                false, "", "", Optional.empty(), "CUSTOMER");

        ContextResponse response = orchestrator().buildContext(request, context(), caller());

        assertThat(audited).singleElement().satisfies(e -> assertThat(e.entityType()).isEqualTo("CUSTOMER"));
        assertThat(response.entityType()).isEqualTo("ACC-1");
        assertThat(sourceSaw).containsExactly("ACC-1");
    }

    @Test
    @DisplayName("an orchestrator DENY audits the audited value, not the raw one")
    void denyAuditsTheAuditedValue() {
        DefaultContextOrchestrator noData = noDataOrchestrator();
        assertThatThrownBy(() -> noData.buildContext(ContextRequest.of("ACC-1", "1"), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class);

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.policyDecision()).isEqualTo("DENY:NO_SOURCE_DATA");
            assertThat(e.entityType()).isEqualTo(AuditedEntityTypes.UNREGISTERED);
        });
    }

    private DefaultContextOrchestrator noDataOrchestrator() {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ObjectMapper mapper = new ObjectMapper();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(mapper.createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        DataSourceAdapter<Thing> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "source-a";
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                return null;
            }
        };
        return new DefaultContextOrchestrator(
                List.of(adapter), scrubber, resolver, List.of(ok, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(audited::add, CLOCK, "test-150"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }
}
