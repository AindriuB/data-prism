package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.correlation.InboundCorrelation;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AdmissionDecision;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every denial path of both tools, when the audit sink throws: one result, {@code refused: AUDIT_UNAVAILABLE},
 * one {@link Metric#DENIAL_AUDIT_UNAVAILABLE}, nothing read, and nothing of the sink's exception in the result.
 */
class DenialAuditUnavailableToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-244-test-key-not-for-any-real-data-32b");
    private static final String SENTINEL = "sentinel-sink-failure-/var/audit/secret.log";
    private static final Map<String, Object> ARGS = Map.of("entityType", "THING", "subjectId", "1");

    private record Thing(String id, String value) {
    }

    private final AtomicInteger fetches = new AtomicInteger();
    private final List<Metric> incremented = new ArrayList<>();
    private final AuditRecorder throwingAudit = new AuditRecorder(event -> {
        throw new IllegalStateException(SENTINEL);
    }, FIXED, "test-mcp");

    private final PrivacyMetrics metrics = new PrivacyMetrics() {
        @Override
        public void increment(Metric metric) {
            incremented.add(metric);
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            incremented.add(metric);
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
        }
    };

    private DefaultContextOrchestrator orchestrator() {
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
                fetches.incrementAndGet();
                throw new IllegalStateException("source down");
            }
        };
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(JsonMapper.builder().build().createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        return new DefaultContextOrchestrator(List.of(adapter), scrubber, resolver, List.of(ok),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS), throwingAudit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none(), OrchestrationOptions.defaults());
    }

    private static final ToolAdmission REFUSING = new ToolAdmission(new InMemoryOversightState(),
            new InMemoryApprovalStore(), new InMemoryCallerRateLimiter(), OversightPolicy.none(), FIXED) {
        @Override
        public AdmissionDecision admit(AuthenticatedCaller caller, String tool, String scopeId, String binding) {
            return AdmissionDecision.refuse("PAUSED");
        }
    };

    private McpSchema.CallToolResult call(String tool, String path) {
        boolean noCapability = path.equals("authorisation");
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"), Map.of("investigator",
                noCapability ? Set.<String>of() : Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        Set<String> purposes = path.equals("scope") ? Set.of("other") : Set.of("demonstration");
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(purposes), CaseAuthorizer.tokenTrusted());

        ToolOptions.Builder options = ToolOptions.defaults();
        if (path.equals("admission")) {
            options.admission(REFUSING, new ParameterFingerprinter(KEYS));
        } else {
            options.noAdmission();
        }
        if (path.startsWith("correlation")) {
            options.correlationRequirement(CorrelationRequirement.REQUIRED);
        }

        boolean unauthenticated = path.equals("unauthenticated");
        Map<String, Object> entries = new HashMap<>();
        if (!unauthenticated) {
            entries.put(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, new AuthenticatedCaller(
                    "principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null));
        }
        if (path.equals("correlation-invalid")) {
            entries.put(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY, InboundCorrelation.rejected());
        }
        McpTransportContext context = McpTransportContext.create(entries);

        McpServerFeatures.SyncToolSpecification spec = tool.equals(GetEntityContextTool.NAME)
                ? new GetEntityContextTool(orchestrator(), authz, scopes, metrics, throwingAudit, FIXED, null,
                        options.build()).specification()
                : new CompareEntitySourcesTool(orchestrator(), authz, scopes, metrics, throwingAudit, FIXED, null,
                        options.build()).specification();
        return spec.callHandler().apply(
                new McpSyncServerExchange(new McpAsyncServerExchange("s", null, null, null, context)),
                new McpSchema.CallToolRequest(tool, ARGS));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @DisplayName("a denial whose audit write fails answers refused: AUDIT_UNAVAILABLE and counts it once")
    @CsvSource({
            "get_entity_context, unauthenticated", "get_entity_context, correlation-required",
            "get_entity_context, correlation-invalid", "get_entity_context, authorisation",
            "get_entity_context, scope", "get_entity_context, admission", "get_entity_context, orchestrator",
            "compare_entity_sources, unauthenticated", "compare_entity_sources, correlation-required",
            "compare_entity_sources, correlation-invalid", "compare_entity_sources, authorisation",
            "compare_entity_sources, scope", "compare_entity_sources, admission",
            "compare_entity_sources, orchestrator"})
    void denialWhoseAuditFailsIsAuditUnavailable(String tool, String path) {
        McpSchema.CallToolResult result = call(tool, path);

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("refused: AUDIT_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain(SENTINEL).doesNotContain("IllegalStateException");
        assertThat(incremented).filteredOn(m -> m == Metric.DENIAL_AUDIT_UNAVAILABLE).hasSize(1);
        if (!path.equals("orchestrator")) {
            assertThat(fetches).as("no adapter call follows a denial").hasValue(0);
        }
    }
}
