package io.github.aindriub.dataprism.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal the real orchestrator audits is returned with that audit event's
 * correlation id in {@code _meta}, on every refusal path and on both tools.
 */
class OrchestratorRefusalCorrelationTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String META_KEY = "io.github.aindriub.dataprism/correlationId";
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-101-test-key-not-for-any-real-data-32b");
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final Map<String, Object> ARGS = Map.of("entityType", "THING", "subjectId", "1");

    private record Thing(String id, String value) {
    }

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");

    private static DataSourceAdapter<Thing> source(Thing thing) {
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
                return thing;
            }
        };
    }

    private DefaultContextOrchestrator orchestrator(Thing thing, ScrubbingEngine scrubber,
                                                    LlmResponseValidator validator, ScopeBudget budget) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        return new DefaultContextOrchestrator(List.of(source(thing)), scrubber, resolver,
                List.of(validator, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS), audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                budget, RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private static final ScrubbingEngine OK_SCRUBBER = (source, ctx) ->
            new ScrubResult(new ObjectMapper().createObjectNode().put("value", "ok"), Set.of());
    private static final LlmResponseValidator OK = (response, prohibited, emitted, ctx) -> ValidationResult.ok();

    private List<BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>> handlers(
            DefaultContextOrchestrator orchestrator) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")));
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), PrivacyMetrics.none(), audit, FIXED);
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), PrivacyMetrics.none(), audit, FIXED);
        return List.of(get.specification().callHandler(), compare.specification().callHandler());
    }

    private void assertEveryToolCarriesTheAuditedDeny(DefaultContextOrchestrator orchestrator, String refusalText,
                                                         String expectedDecision) {
        String[] names = {GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME};
        var handlers = handlers(orchestrator);
        for (int i = 0; i < 2; i++) {
            audited.clear();
            McpTransportContext context = McpTransportContext.create(
                    Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, CALLER));
            McpSchema.CallToolResult result = handlers.get(i).apply(
                    new McpSyncServerExchange(new McpAsyncServerExchange("s", null, null, null, context)),
                    new McpSchema.CallToolRequest(names[i], ARGS));
            assertThat(result.isError()).as(names[i]).isEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).as(names[i])
                    .startsWith(refusalText).doesNotContain("scrubber down");
            assertThat(audited).as(names[i]).hasSize(1);
            assertThat(audited.get(0).policyDecision()).isEqualTo(expectedDecision);
            assertThat(result.meta()).as(names[i]).isNotNull();
            assertThat(result.meta().get(META_KEY))
                    .as(names[i]).isEqualTo(audited.get(0).correlationId());
        }
    }

    @Test
    @DisplayName("a VALIDATION_FAILED refusal carries the audited correlationId")
    void validationFailedCarriesCorrelation() {
        LlmResponseValidator fails = (response, prohibited, emitted, ctx) ->
                ValidationResult.failed(List.of(new Violation("value", "LEAKED", "test")));
        assertEveryToolCarriesTheAuditedDeny(
                orchestrator(new Thing("1", "raw"), OK_SCRUBBER, fails, new InMemoryScopeBudget()),
                "refused: VALIDATION_FAILED", "DENY:VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a NO_SOURCE_DATA refusal carries the audited correlationId")
    void noSourceDataCarriesCorrelation() {
        assertEveryToolCarriesTheAuditedDeny(
                orchestrator(null, OK_SCRUBBER, OK, new InMemoryScopeBudget()),
                "refused: NO_SOURCE_DATA", "DENY:NO_SOURCE_DATA");
    }

    @Test
    @DisplayName("a SCOPE_READ_BUDGET refusal carries the audited correlationId")
    void scopeReadBudgetCarriesCorrelation() {
        ScopeBudget exhausted = new ScopeBudget() {
            @Override
            public boolean tryRead(String scopeId, String subjectId, int budget) {
                return false;
            }

            @Override
            public int reads(String scopeId, String subjectId) {
                return 0;
            }

            @Override
            public void forget(String scopeId) {
            }
        };
        assertEveryToolCarriesTheAuditedDeny(
                orchestrator(new Thing("1", "raw"), OK_SCRUBBER, OK, exhausted), "refused: SCOPE_READ_BUDGET",
                "DENY:SCOPE_READ_BUDGET");
    }

    @Test
    @DisplayName("an unexpected failure the orchestrator audited as DENY carries the audited correlationId")
    void unexpectedFailureCarriesCorrelation() {
        ScrubbingEngine broken = (source, ctx) -> {
            throw new IllegalStateException("scrubber down");
        };
        assertEveryToolCarriesTheAuditedDeny(
                orchestrator(new Thing("1", "raw"), broken, OK, new InMemoryScopeBudget()),
                "the request could not be completed", "DENY:REQUEST_FAILED");
    }
}
