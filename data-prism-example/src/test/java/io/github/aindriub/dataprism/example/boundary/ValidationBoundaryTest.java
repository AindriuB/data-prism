package io.github.aindriub.dataprism.example.boundary;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boundary 3: validation failures must withhold the response and audit a denial. */
class ValidationBoundaryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");

    @Test
    @DisplayName("a response that fails validation is refused and audited rather than returned")
    void validationFailureIsNotReturned() {
        List<AuditEvent> audited = new ArrayList<>();
        LlmResponseValidator alwaysFails = (response, prohibited, emitted, context) ->
                ValidationResult.failed(List.of(new Violation("value", "LEAKED", "test")));

        DefaultContextOrchestrator orchestrator = orchestrator(alwaysFails, audited);

        assertThatThrownBy(() -> orchestrator.buildContext(
                ContextRequest.of("THING", "1"), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining("VALIDATION_FAILED");
        assertThat(audited).singleElement()
                .extracting(AuditEvent::policyDecision)
                .isEqualTo("DENY");
    }

    private static DefaultContextOrchestrator orchestrator(LlmResponseValidator validator,
                                                            List<AuditEvent> audited) {
        ObjectMapper mapper = new ObjectMapper();
        ScrubbingEngine scrubber = (source, context) ->
                new ScrubResult(mapper.createObjectNode().put("value", "safe"), Set.of());

        return new DefaultContextOrchestrator(
                List.of(answering()), scrubber, new DefaultFieldMetadataResolver(), validator,
                (subjectId, namespace, context) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(audited::add, CLOCK, "boundary-test"),
                new SourceAliasing(new HmacValueTokenSource(KEYS)));
    }

    private static DataSourceAdapter<Thing> answering() {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "thing-api";
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                return new Thing("1", "raw");
            }
        };
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private record Thing(String id, String value) {
    }
}
