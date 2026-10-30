package io.github.aindriub.dataprism.orchestration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A DENY whose audit write fails surfaces as {@link DenialAuditFailedException}, carrying nothing from the sink. */
class DenialAuditFailureTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
    private static final String SENTINEL = "sentinel-sink-path-/var/audit/x.log";

    @LlmExposedModel
    private record Thing(@InternalIdentifier String id, String value) {
    }

    private static DefaultContextOrchestrator orchestrator(boolean failingSource, boolean sinkThrows,
                                                            List<AuditEvent> events) {
        DataSourceAdapter<Thing> adapter = new DataSourceAdapter<>() {
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
                if (failingSource) {
                    throw new IllegalStateException("source down");
                }
                return new Thing("1", "raw");
            }
        };
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(JsonMapper.builder().build().createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator pass = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        return new DefaultContextOrchestrator(List.of(adapter), scrubber, resolver, List.of(pass),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(event -> {
                    if (sinkThrows) {
                        throw new IllegalStateException(SENTINEL);
                    }
                    events.add(event);
                }, CLOCK, "test-1"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none(), OrchestrationOptions.defaults());
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    @Test
    @DisplayName("a DENY whose audit write fails throws DenialAuditFailedException with no text from the sink")
    void denyAuditFailureThrowsDenialAuditFailed() {
        var orchestrator = orchestrator(true, true, new ArrayList<>());

        Throwable thrown = catchThrowable(() ->
                orchestrator.buildContext(ContextRequest.of("THING", "1"), context(), caller()));

        assertThat(thrown).isInstanceOf(DenialAuditFailedException.class);
        assertThat(thrown.getMessage()).isEqualTo("AUDIT_UNAVAILABLE").doesNotContain(SENTINEL);
        assertThat(thrown.getCause()).as("the sink's exception is never the cause").isNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(t.toString()).doesNotContain(SENTINEL);
        }
    }

    @Test
    @DisplayName("a DENY whose audit write succeeds is still an AuditedRefusalException")
    void auditedDenyIsUnchanged() {
        var events = new ArrayList<AuditEvent>();
        var orchestrator = orchestrator(true, false, events);

        Throwable thrown = catchThrowable(() ->
                orchestrator.buildContext(ContextRequest.of("THING", "1"), context(), caller()));

        assertThat(thrown).isInstanceOf(AuditedRefusalException.class);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("the log line for a failed DENY write names the refusal code and none of the failure's payload")
    void logNamesTheRefusalCodeOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultContextOrchestrator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var orchestrator = orchestrator(true, true, new ArrayList<>());
            catchThrowable(() -> orchestrator.buildContext(ContextRequest.of("THING", "1"), context(), caller()));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).contains("NO_SOURCE_DATA").doesNotContain("source down").doesNotContain(SENTINEL);
    }
}
