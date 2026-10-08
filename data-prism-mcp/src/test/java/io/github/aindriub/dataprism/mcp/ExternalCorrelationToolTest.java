package io.github.aindriub.dataprism.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
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
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.InboundCorrelation;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
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
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Both tools read the external correlation id from the transport context only, and never from arguments. */
class ExternalCorrelationToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-110-test-key-not-for-any-real-data-32b");
    private static final CorrelationIdPolicy POLICY =
            CorrelationIdPolicy.opaque("ext-[a-z0-9-]{1,40}");
    private static final String META_KEY = "io.github.aindriub.dataprism/correlationId";
    private static final String[] TOOLS = {GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME};
    private static final Map<String, Object> ARGS = Map.of("entityType", "THING", "subjectId", "1");
    private static final InboundCorrelation PRESENT = InboundCorrelation.present(POLICY.validate("ext-abc-123").orElseThrow());
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final AuthenticatedCaller UNPRIVILEGED =
            new AuthenticatedCaller("principal-2", "client-2", Set.of("nobody"), "demonstration", "case-1", null);

    private record Thing(String id, String value) {
    }

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
    private final List<Metric> incremented = new ArrayList<>();
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
    private final List<ContextRequest> invoked = new ArrayList<>();

    /** Counts invocations and returns a stub; never audits, so every audit event under test is the tool's own. */
    private final ContextOrchestrator recording = (request, privacyContext, investigationContext) -> {
        invoked.add(request);
        return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(), List.of(),
                DataPrismObjectMapper.create().createObjectNode(), Map.of(), "stub-correlation");
    };

    private static DefaultContextOrchestrator real(AuditRecorder audit, Thing thing) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(new ObjectMapper().createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        DataSourceAdapter<Thing> source = new DataSourceAdapter<>() {
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
        return new DefaultContextOrchestrator(List.of(source), scrubber, resolver,
                List.of(ok, new SensitivePatternValidator()), (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS), audit, new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private List<BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>> handlers(
            ContextOrchestrator orchestrator, CorrelationRequirement requirement, ToolAdmission admission) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        ParameterFingerprinter fingerprinter = new ParameterFingerprinter(KEYS);
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, admission, fingerprinter, requirement);
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, admission, fingerprinter, requirement);
        return List.of(get.specification().callHandler(), compare.specification().callHandler());
    }

    private McpSchema.CallToolResult call(int tool, ContextOrchestrator orchestrator,
                                          CorrelationRequirement requirement, ToolAdmission admission,
                                          AuthenticatedCaller caller, Object inbound, Map<String, Object> args) {
        Map<String, Object> entries = new HashMap<>();
        if (caller != null) {
            entries.put(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller);
        }
        if (inbound != null) {
            entries.put(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY, inbound);
        }
        McpSyncServerExchange exchange = new McpSyncServerExchange(
                new McpAsyncServerExchange("s", null, null, null, McpTransportContext.create(entries)));
        return handlers(orchestrator, requirement, admission).get(tool)
                .apply(exchange, new McpSchema.CallToolRequest(TOOLS[tool], args));
    }

    private McpSchema.CallToolResult call(int tool, Object inbound, CorrelationRequirement requirement) {
        return call(tool, recording, requirement, ToolAdmission.none(), CALLER, inbound, ARGS);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private void reset() {
        audited.clear();
        incremented.clear();
        invoked.clear();
    }

    @Test
    @DisplayName("REQUIRED with no id refuses with EXTERNAL_CORRELATION_ID_REQUIRED, audited, counted, no orchestrator call")
    void requiredAbsentRefuses() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            McpSchema.CallToolResult result = call(tool, InboundCorrelation.absent(), CorrelationRequirement.REQUIRED);

            assertThat(result.isError()).as(TOOLS[tool]).isEqualTo(Boolean.TRUE);
            assertThat(text(result)).isEqualTo("EXTERNAL_CORRELATION_ID_REQUIRED");
            assertThat(invoked).isEmpty();
            assertThat(incremented).containsExactly(Metric.MCP_DENIED);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("DENY:EXTERNAL_CORRELATION_ID_REQUIRED");
                assertThat(e.externalCorrelationId()).isEmpty();
                assertThat(result.meta().get(META_KEY)).isEqualTo(e.correlationId());
            });
        }
    }

    @Test
    @DisplayName("REQUIRED with a missing transport-context entry is treated as absent")
    void requiredMissingEntryRefuses() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            McpSchema.CallToolResult result = call(tool, null, CorrelationRequirement.REQUIRED);

            assertThat(text(result)).isEqualTo("EXTERNAL_CORRELATION_ID_REQUIRED");
            assertThat(invoked).isEmpty();
        }
    }

    @Test
    @DisplayName("REQUIRED with a rejected id refuses with EXTERNAL_CORRELATION_ID_INVALID, audited, counted, no orchestrator call")
    void requiredRejectedRefuses() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            McpSchema.CallToolResult result = call(tool, InboundCorrelation.rejected(), CorrelationRequirement.REQUIRED);

            assertThat(result.isError()).as(TOOLS[tool]).isEqualTo(Boolean.TRUE);
            assertThat(text(result)).isEqualTo("EXTERNAL_CORRELATION_ID_INVALID");
            assertThat(invoked).isEmpty();
            assertThat(incremented).containsExactly(Metric.MCP_DENIED);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("DENY:EXTERNAL_CORRELATION_ID_INVALID");
                assertThat(e.externalCorrelationId()).isEmpty();
                assertThat(result.meta().get(META_KEY)).isEqualTo(e.correlationId());
            });
        }
    }

    @Test
    @DisplayName("a transport-context value that is not an InboundCorrelation is treated as absent")
    void wrongTypeIsAbsent() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            McpSchema.CallToolResult required = call(tool, "ext-abc-123", CorrelationRequirement.REQUIRED);
            assertThat(text(required)).isEqualTo("EXTERNAL_CORRELATION_ID_REQUIRED");
            assertThat(invoked).isEmpty();

            reset();
            call(tool, "ext-abc-123", CorrelationRequirement.OPTIONAL);
            assertThat(invoked).singleElement().satisfies(r -> assertThat(r.externalCorrelationId()).isEmpty());
        }
    }

    @Test
    @DisplayName("OPTIONAL with a rejected id proceeds with no external id, audited as an empty string")
    void optionalRejectedProceedsWithoutId() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            McpSchema.CallToolResult result = call(tool, real(audit, new Thing("1", "raw")),
                    CorrelationRequirement.OPTIONAL, ToolAdmission.none(), CALLER, InboundCorrelation.rejected(), ARGS);

            assertThat(result.isError()).as(TOOLS[tool]).isNotEqualTo(Boolean.TRUE);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("ALLOW");
                assertThat(e.externalCorrelationId()).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("a present id is on the ContextRequest and on the ALLOW event")
    void presentIdOnRequestAndAllow() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            call(tool, recording, CorrelationRequirement.REQUIRED, ToolAdmission.none(), CALLER, PRESENT, ARGS);
            assertThat(invoked).singleElement()
                    .satisfies(r -> assertThat(r.externalCorrelationId()).contains(PRESENT.id().orElseThrow()));

            reset();
            call(tool, real(audit, new Thing("1", "raw")), CorrelationRequirement.REQUIRED, ToolAdmission.none(),
                    CALLER, PRESENT, ARGS);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("ALLOW");
                assertThat(e.externalCorrelationId()).isEqualTo("ext-abc-123");
            });
        }
    }

    @Test
    @DisplayName("a present id is on the DENY event of an orchestrator refusal")
    void presentIdOnOrchestratorRefusal() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            call(tool, real(audit, null), CorrelationRequirement.OPTIONAL, ToolAdmission.none(), CALLER, PRESENT, ARGS);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("DENY:NO_SOURCE_DATA");
                assertThat(e.externalCorrelationId()).isEqualTo("ext-abc-123");
            });
        }
    }

    @Test
    @DisplayName("a present id is on the DENY event of an admission refusal")
    void presentIdOnAdmissionRefusal() {
        ToolAdmission needsApproval = new ToolAdmission(new InMemoryOversightState(), new InMemoryApprovalStore(),
                new InMemoryCallerRateLimiter(),
                new OversightPolicy(Set.of(TOOLS), OptionalInt.empty(), Duration.ofMinutes(1), Duration.ofHours(1)),
                FIXED);
        for (int tool = 0; tool < 2; tool++) {
            reset();
            call(tool, recording, CorrelationRequirement.OPTIONAL, needsApproval, CALLER, PRESENT, ARGS);
            assertThat(invoked).isEmpty();
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).startsWith("DENY:");
                assertThat(e.externalCorrelationId()).isEqualTo("ext-abc-123");
            });
        }
    }

    @Test
    @DisplayName("a present id is on the DENY event of an authorisation refusal and of an unauthenticated one")
    void presentIdOnAuthorisationAndAuthenticationRefusals() {
        for (int tool = 0; tool < 2; tool++) {
            reset();
            call(tool, recording, CorrelationRequirement.OPTIONAL, ToolAdmission.none(), UNPRIVILEGED, PRESENT, ARGS);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).startsWith("DENY:");
                assertThat(e.externalCorrelationId()).isEqualTo("ext-abc-123");
            });

            reset();
            call(tool, recording, CorrelationRequirement.OPTIONAL, ToolAdmission.none(), null, PRESENT, ARGS);
            assertThat(audited).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("DENY:NO_AUTHENTICATED_CALLER");
                assertThat(e.externalCorrelationId()).isEqualTo("ext-abc-123");
            });
            assertThat(invoked).isEmpty();
        }
    }

    @Test
    @DisplayName("the id never appears in structuredContent or text content")
    void idNeverInResult() {
        for (int tool = 0; tool < 2; tool++) {
            McpSchema.CallToolResult result = call(tool, real(audit, new Thing("1", "raw")),
                    CorrelationRequirement.REQUIRED, ToolAdmission.none(), CALLER, PRESENT, ARGS);
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).doesNotContain("ext-abc-123");
            assertThat(String.valueOf(result.structuredContent())).doesNotContain("ext-abc-123");
        }
    }

    @Test
    @DisplayName("correlation names sent as arguments are reported as rejected and never become the audited id")
    void argumentsAreNeverASource() {
        Map<String, Object> args = Map.of("entityType", "THING", "subjectId", "1",
                "correlationId", "arg-id-1", "externalCorrelationId", "arg-id-2", "traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        for (int tool = 0; tool < 2; tool++) {
            for (Object inbound : new Object[] {PRESENT, InboundCorrelation.absent(), null}) {
                reset();
                call(tool, real(audit, new Thing("1", "raw")), CorrelationRequirement.OPTIONAL, ToolAdmission.none(),
                        CALLER, inbound, args);
                assertThat(audited).singleElement().satisfies(e -> {
                    assertThat(e.rejectedArguments())
                            .containsExactlyInAnyOrder("correlationId", "externalCorrelationId", "traceparent");
                    assertThat(e.externalCorrelationId()).isIn("ext-abc-123", "");
                    assertThat(e.externalCorrelationId().equals("ext-abc-123")).isEqualTo(inbound == PRESENT);
                });
            }
        }
        // Under REQUIRED, arguments cannot satisfy the requirement either.
        reset();
        McpSchema.CallToolResult result = call(0, recording, CorrelationRequirement.REQUIRED, ToolAdmission.none(),
                CALLER, InboundCorrelation.absent(), args);
        assertThat(text(result)).isEqualTo("EXTERNAL_CORRELATION_ID_REQUIRED");
        assertThat(invoked).isEmpty();
    }

    @Test
    @DisplayName("tools/list is identical whatever the requirement, and declares no correlation argument")
    void toolsListUnchanged() {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        McpSyncServer optional = DataPrismMcpServer.stdio(recording, authz, scopes, CALLER, true, false,
                PrivacyMetrics.none(), audit, FIXED);
        McpSyncServer required = DataPrismMcpServer.stdio(recording, authz, scopes, CALLER, true, false,
                PrivacyMetrics.none(), audit, FIXED, CorrelationRequirement.REQUIRED);
        try {
            assertThat(required.listTools()).isEqualTo(optional.listTools());
            for (McpSchema.Tool tool : required.listTools()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
                assertThat(properties).doesNotContainKeys("correlationId", "externalCorrelationId", "traceparent");
            }
        } finally {
            optional.closeGracefully();
            required.closeGracefully();
        }
    }

    @Test
    @DisplayName("a real admission with a null fingerprinter is refused at construction, on every public overload")
    void nullFingerprinterWithAdmissionIsRefused() {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"), Map.of());
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        ToolAdmission real = new ToolAdmission(new InMemoryOversightState(), new InMemoryApprovalStore(),
                new InMemoryCallerRateLimiter(),
                new OversightPolicy(Set.of(TOOLS), OptionalInt.empty(), Duration.ofMinutes(1), Duration.ofHours(1)),
                FIXED);
        ObjectMapper mapper = DataPrismObjectMapper.create();
        for (CorrelationRequirement requirement : CorrelationRequirement.values()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GetEntityContextTool(recording, authz, scopes,
                    mapper, metrics, audit, FIXED, null, real, null, requirement))
                    .isInstanceOf(NullPointerException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CompareEntitySourcesTool(recording, authz,
                    scopes, mapper, metrics, audit, FIXED, null, real, null, requirement))
                    .isInstanceOf(NullPointerException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GetEntityContextTool(recording, authz, scopes,
                mapper, metrics, audit, FIXED, null, real, null)).isInstanceOf(NullPointerException.class);
    }
}
