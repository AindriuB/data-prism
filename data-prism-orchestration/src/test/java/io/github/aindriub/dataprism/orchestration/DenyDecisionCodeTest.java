package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.audit.AuditEvent;
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
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The orchestrator's DENY event carries the refusal code: {@code DENY:<code>} (task 123). */
class DenyDecisionCodeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");

    @LlmExposedModel
    private record Thing(@InternalIdentifier String id, String value) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private static DataSourceAdapter<Thing> adapter(boolean failing) {
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
                if (failing) {
                    throw new IllegalStateException("source down");
                }
                return new Thing("1", "raw");
            }
        };
    }

    private static final ScrubbingEngine OK = (source, ctx) ->
            new ScrubResult(new ObjectMapper().createObjectNode().put("value", "ok"), Set.of());

    private static DefaultContextOrchestrator orchestrator(boolean failingSource, ScrubbingEngine scrubber,
                                                            LlmResponseValidator validator, RequestLimits limits,
                                                            List<AuditEvent> events) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        return new DefaultContextOrchestrator(
                List.of(adapter(failingSource)), scrubber, resolver, List.of(validator),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(events::add, CLOCK, "test-1"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), limits,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
    }

    private static final LlmResponseValidator PASS = (response, prohibited, emitted, ctx) -> ValidationResult.ok();

    /** Runs one call and returns the single recorded decision; the call must throw. */
    private static String decisionOf(DefaultContextOrchestrator orchestrator, List<AuditEvent> events) {
        Throwable thrown = catchThrowable(() ->
                orchestrator.buildContext(ContextRequest.of("THING", "1"), context(), caller()));
        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(events).hasSize(1);
        return events.get(0).policyDecision();
    }

    @Test
    @DisplayName("an exhausted read budget records DENY:SCOPE_READ_BUDGET")
    void scopeReadBudget() {
        var events = new ArrayList<AuditEvent>();
        var limits = new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(3), 4, 1);
        var orchestrator = orchestrator(false, OK, PASS, limits, events);
        orchestrator.buildContext(ContextRequest.of("THING", "1"), context(), caller());
        events.clear();

        assertThat(decisionOf(orchestrator, events)).isEqualTo("DENY:SCOPE_READ_BUDGET");
    }

    @Test
    @DisplayName("every source failing records DENY:NO_SOURCE_DATA")
    void noSourceData() {
        var events = new ArrayList<AuditEvent>();

        assertThat(decisionOf(orchestrator(true, OK, PASS, RequestLimits.DEFAULT, events), events))
                .isEqualTo("DENY:NO_SOURCE_DATA");
    }

    @Test
    @DisplayName("a validator failure records DENY:VALIDATION_FAILED")
    void validationFailed() {
        var events = new ArrayList<AuditEvent>();
        LlmResponseValidator refusing = (response, prohibited, emitted, ctx) ->
                ValidationResult.failed(List.of(new Violation("/value", "X", "TEST")));

        assertThat(decisionOf(orchestrator(false, OK, refusing, RequestLimits.DEFAULT, events), events))
                .isEqualTo("DENY:VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a scrubber refusal records its code, DENY:UNKNOWN_FIELD")
    void scrubRefusal() {
        var events = new ArrayList<AuditEvent>();
        ScrubbingEngine refusing = (source, ctx) -> {
            throw new PrivacyRefusedException("UNKNOWN_FIELD", "/x", "undeclared");
        };

        assertThat(decisionOf(orchestrator(false, refusing, PASS, RequestLimits.DEFAULT, events), events))
                .isEqualTo("DENY:UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("a scrubber that throws IllegalStateException records DENY:REQUEST_FAILED")
    void internalFailure() {
        var events = new ArrayList<AuditEvent>();
        ScrubbingEngine broken = (source, ctx) -> {
            throw new IllegalStateException("boom");
        };

        assertThat(decisionOf(orchestrator(false, broken, PASS, RequestLimits.DEFAULT, events), events))
                .isEqualTo("DENY:REQUEST_FAILED");
    }

    @Test
    @DisplayName("a code that is not [A-Z][A-Z0-9_]{0,63} records DENY:INVALID_REFUSAL_CODE; the exception is unchanged")
    void malformedCodes() {
        for (String bad : List.of("bad code", "A".repeat(65), "lower", "", "9START", "WITH:COLON")) {
            var events = new ArrayList<AuditEvent>();
            ScrubbingEngine refusing = (source, ctx) -> {
                throw new PrivacyRefusedException(bad, "/x", "detail");
            };
            var orchestrator = orchestrator(false, refusing, PASS, RequestLimits.DEFAULT, events);

            Throwable thrown = catchThrowable(() ->
                    orchestrator.buildContext(ContextRequest.of("THING", "1"), context(), caller()));

            assertThat(events).as(bad).hasSize(1);
            assertThat(events.get(0).policyDecision()).as(bad).isEqualTo("DENY:INVALID_REFUSAL_CODE");
            assertThat(thrown).as(bad).isInstanceOf(AuditedRefusalException.class)
                    .isInstanceOf(PrivacyRefusedException.class);
            assertThat(((PrivacyRefusedException) thrown).code()).as(bad).isEqualTo(bad);
        }
    }

    @Test
    @DisplayName("a 64-character code is accepted as written")
    void longestValidCode() {
        var events = new ArrayList<AuditEvent>();
        String code = "A".repeat(64);
        ScrubbingEngine refusing = (source, ctx) -> {
            throw new PrivacyRefusedException(code, "/x", "detail");
        };

        assertThat(decisionOf(orchestrator(false, refusing, PASS, RequestLimits.DEFAULT, events), events))
                .isEqualTo("DENY:" + code);
    }
}
