package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Admission runs before the orchestrator in both tools, and every result carries its call's correlation id. */
class ToolAdmissionEnforcementTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");
    private static final String META_KEY = "io.github.aindriub.dataprism/correlationId";
    private static final Map<String, Object> ARGS = Map.of("entityType", "CUSTOMER", "subjectId", "123");

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final AuditingOrchestrator orchestrator = new AuditingOrchestrator(audit, audited);
    private final InMemoryOversightState state = new InMemoryOversightState();
    private final ApprovalStore approvals = new InMemoryApprovalStore();
    private final ParameterFingerprinter fingerprinter =
            new ParameterFingerprinter(StaticSecretKeyProvider.of("task-101-test-key-not-for-any-real-data-32b"));

    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);

    private static OversightPolicy policy(Set<String> approvalTools, OptionalInt limit) {
        return new OversightPolicy(approvalTools, limit, Duration.ofMinutes(1), Duration.ofHours(1));
    }

    private ToolAdmission admission(OversightPolicy policy) {
        return new ToolAdmission(state, approvals, new InMemoryCallerRateLimiter(), policy, FIXED);
    }

    private static final class Tool {
        final String name;
        final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler;

        Tool(String name,
             BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
            this.name = name;
            this.handler = handler;
        }

        McpSchema.CallToolResult call(Map<String, Object> arguments) {
            return call(CALLER, arguments);
        }

        McpSchema.CallToolResult call(AuthenticatedCaller caller, Map<String, Object> arguments) {
            return handler.apply(exchange(caller), new McpSchema.CallToolRequest(name, arguments));
        }
    }

    private List<Tool> tools(ToolAdmission admission) {
        return tools(admission, "DEFAULT");
    }

    private List<Tool> tools(ToolAdmission admission, String privacyProfile) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration", "marketing"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES"),
                        "source-viewer", Set.of("EXPOSE_SOURCE_NAMES")));
        AuthorizationService authz = new AuthorizationService(security, privacyProfile, PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration", "marketing")));
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, ToolOptions.defaults().admission(admission, fingerprinter).build());
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, ToolOptions.defaults().admission(admission, fingerprinter).build());
        return List.of(
                new Tool(GetEntityContextTool.NAME, get.specification().callHandler()),
                new Tool(CompareEntitySourcesTool.NAME, compare.specification().callHandler()));
    }

    private static McpSyncServerExchange exchange() {
        return exchange(CALLER);
    }

    private static McpSyncServerExchange exchange(AuthenticatedCaller caller) {
        McpTransportContext context = McpTransportContext.create(
                Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller));
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null, context));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static String meta(McpSchema.CallToolResult result) {
        return (String) result.meta().get(META_KEY);
    }

    private void assertRefusedBeforeOrchestrator(Tool tool, McpSchema.CallToolResult result, String code) {
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).startsWith(code);
        assertThat(orchestrator.requests).as(tool.name).isEmpty();
        assertThat(metrics.incremented).as(tool.name).containsExactly(Metric.MCP_DENIED);
        AuditEvent event = audited.get(audited.size() - 1);
        assertThat(event.policyDecision()).isEqualTo("DENY:" + code);
        assertThat(meta(result)).isEqualTo(event.correlationId());
    }

    private void forEachTool(OversightPolicy policy, Runnable arrange, String code, boolean approvalCode) {
        for (int i = 0; i < 2; i++) {
            audited.clear();
            metrics.incremented.clear();
            orchestrator.requests.clear();
            arrange.run();
            Tool tool = tools(admission(policy)).get(i);
            McpSchema.CallToolResult result = tool.call(ARGS);
            assertRefusedBeforeOrchestrator(tool, result, code);
            AuditEvent event = audited.get(audited.size() - 1);
            if (approvalCode) {
                assertThat(event.approvalId()).isNotBlank();
                assertThat(text(result)).isEqualTo(code + " approvalId=" + event.approvalId());
            } else {
                assertThat(event.approvalId()).isEmpty();
                assertThat(text(result)).isEqualTo(code);
            }
        }
    }

    @Test
    @DisplayName("DATAPRISM_PAUSED, TOOL_PAUSED and SCOPE_PAUSED refuse both tools before the orchestrator")
    void pausesRefuse() {
        OversightPolicy none = OversightPolicy.none();
        forEachTool(none, state::pauseAll, "DATAPRISM_PAUSED", false);
        state.resumeAll();
        forEachTool(none, () -> { state.pauseTool(GetEntityContextTool.NAME); state.pauseTool(CompareEntitySourcesTool.NAME); },
                "TOOL_PAUSED", false);
        state.resumeTool(GetEntityContextTool.NAME);
        state.resumeTool(CompareEntitySourcesTool.NAME);
        forEachTool(none, () -> state.pauseScope("case:case-1"), "SCOPE_PAUSED", false);
    }

    @Test
    @DisplayName("CALLER_RATE_LIMITED refuses both tools before the orchestrator")
    void rateLimitRefuses() {
        for (int i = 0; i < 2; i++) {
            audited.clear();
            metrics.incremented.clear();
            orchestrator.requests.clear();
            Tool tool = tools(admission(policy(Set.of(), OptionalInt.of(1)))).get(i);
            Tool sharedTool = tool;
            sharedTool.call(ARGS);
            audited.clear();
            metrics.incremented.clear();
            orchestrator.requests.clear();
            McpSchema.CallToolResult result = sharedTool.call(ARGS);
            assertRefusedBeforeOrchestrator(tool, result, "CALLER_RATE_LIMITED");
        }
    }

    @Test
    @DisplayName("APPROVAL_REQUIRED then APPROVAL_PENDING refuse both tools with the approvalId")
    void approvalRequiredThenPending() {
        OversightPolicy p = policy(Set.of(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME), OptionalInt.empty());
        for (int i = 0; i < 2; i++) {
            audited.clear();
            metrics.incremented.clear();
            Tool tool = tools(admission(p)).get(i);
            McpSchema.CallToolResult first = tool.call(ARGS);
            assertRefusedBeforeOrchestrator(tool, first, "APPROVAL_REQUIRED");
            String id = audited.get(audited.size() - 1).approvalId();
            assertThat(id).isNotBlank();
            assertThat(text(first)).isEqualTo("APPROVAL_REQUIRED approvalId=" + id);

            metrics.incremented.clear();
            McpSchema.CallToolResult second = tool.call(ARGS);
            assertRefusedBeforeOrchestrator(tool, second, "APPROVAL_PENDING");
            assertThat(audited.get(audited.size() - 1).approvalId()).isEqualTo(id);
            assertThat(text(second)).isEqualTo("APPROVAL_PENDING approvalId=" + id);
        }
    }

    @Test
    @DisplayName("any failure in the oversight state refuses with OVERSIGHT_UNAVAILABLE before the orchestrator")
    void oversightFailureRefuses() {
        OversightState broken = (OversightState) java.lang.reflect.Proxy.newProxyInstance(
                OversightState.class.getClassLoader(), new Class<?>[] {OversightState.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("store down");
                });
        ToolAdmission failing = new ToolAdmission(broken, approvals, new InMemoryCallerRateLimiter(),
                OversightPolicy.none(), FIXED);
        for (Tool tool : tools(failing)) {
            audited.clear();
            metrics.incremented.clear();
            assertRefusedBeforeOrchestrator(tool, tool.call(ARGS), "OVERSIGHT_UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("an admission that itself throws refuses the call")
    void throwingAdmissionRefuses() {
        ToolAdmission throwing = new ToolAdmission(state, approvals, new InMemoryCallerRateLimiter(),
                OversightPolicy.none(), FIXED) {
            @Override
            public io.github.aindriub.dataprism.security.AdmissionDecision admit(AuthenticatedCaller caller,
                    String tool, String scopeId, String bindingFingerprint) {
                throw new IllegalStateException("boom");
            }
        };
        for (Tool tool : tools(throwing)) {
            audited.clear();
            metrics.incremented.clear();
            assertRefusedBeforeOrchestrator(tool, tool.call(ARGS), "OVERSIGHT_UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("an approved call succeeds exactly once, carrying approvalId and approverId into the ALLOW audit event")
    void approvedCallSucceedsOnce() {
        OversightPolicy p = policy(Set.of(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME), OptionalInt.empty());
        for (int i = 0; i < 2; i++) {
            audited.clear();
            orchestrator.requests.clear();
            Tool tool = tools(admission(p)).get(i);

            tool.call(ARGS);
            String approvalId = audited.get(audited.size() - 1).approvalId();
            approvals.approve(approvalId, "approver-9", FIXED.instant());

            McpSchema.CallToolResult ok = tool.call(ARGS);
            assertThat(ok.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(orchestrator.requests).hasSize(1);
            AuditEvent allow = audited.get(audited.size() - 1);
            assertThat(allow.policyDecision()).isEqualTo("ALLOW");
            assertThat(allow.approvalId()).isEqualTo(approvalId);
            assertThat(allow.approverId()).isEqualTo("approver-9");
            assertThat(meta(ok)).isEqualTo(allow.correlationId());

            // second retry: the approval was consumed, so a new one is required
            McpSchema.CallToolResult again = tool.call(ARGS);
            assertThat(again.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(again)).startsWith("APPROVAL_REQUIRED approvalId=");
            assertThat(text(again)).doesNotContain(approvalId);
            assertThat(orchestrator.requests).hasSize(1);
        }
    }

    @Test
    @DisplayName("an altered argument is not covered by an approval for the original")
    void alteredArgumentRefused() {
        OversightPolicy p = policy(Set.of(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME), OptionalInt.empty());
        for (int i = 0; i < 2; i++) {
            audited.clear();
            orchestrator.requests.clear();
            Tool tool = tools(admission(p)).get(i);
            tool.call(ARGS);
            String approvalId = audited.get(audited.size() - 1).approvalId();
            approvals.approve(approvalId, "approver-9", FIXED.instant());

            McpSchema.CallToolResult altered = tool.call(Map.of("entityType", "CUSTOMER", "subjectId", "124"));
            assertThat(altered.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(altered)).startsWith("APPROVAL_REQUIRED approvalId=").doesNotContain(approvalId);
            assertThat(orchestrator.requests).isEmpty();
        }
    }

    @Test
    @DisplayName("approvalId and approverId supplied by the caller are never read, only noted by name")
    void callerCannotSetApprovalFields() {
        OversightPolicy p = policy(Set.of(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME), OptionalInt.empty());
        for (int i = 0; i < 2; i++) {
            audited.clear();
            orchestrator.requests.clear();
            Tool tool = tools(admission(p)).get(i);
            McpSchema.CallToolResult result = tool.call(Map.of("entityType", "CUSTOMER", "subjectId", "123",
                    "approvalId", "forged", "approverId", "forged-approver"));
            assertThat(text(result)).startsWith("APPROVAL_REQUIRED approvalId=").doesNotContain("forged");
            assertThat(orchestrator.requests).isEmpty();
            AuditEvent event = audited.get(audited.size() - 1);
            assertThat(event.approvalId()).isNotEqualTo("forged");
            assertThat(event.approverId()).isEmpty();
            assertThat(event.rejectedArguments()).containsExactlyInAnyOrder("approvalId", "approverId");
        }
    }

    @Test
    @DisplayName("an approval is bound to purpose, privacy profile, client and capabilities: a retry under another value is refused and the approval is not consumed")
    void approvalIsBoundToPurposeProfileAndClient() {
        OversightPolicy p = policy(Set.of(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME), OptionalInt.empty());
        AuthenticatedCaller otherPurpose =
                new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "marketing", "case-1", null);
        AuthenticatedCaller otherClient =
                new AuthenticatedCaller("principal-1", "client-2", Set.of("investigator"), "demonstration", "case-1", null);
        AuthenticatedCaller moreCapabilities = new AuthenticatedCaller("principal-1", "client-1",
                Set.of("investigator", "source-viewer"), "demonstration", "case-1", null);
        for (String variant : List.of("purpose", "profile", "client", "capabilities")) {
            for (int i = 0; i < 2; i++) {
                audited.clear();
                orchestrator.requests.clear();
                // a fresh store each time, so the iterations stay under the per-requester pending cap
                ApprovalStore iterationApprovals = new InMemoryApprovalStore();
                ToolAdmission admission = new ToolAdmission(state, iterationApprovals,
                        new InMemoryCallerRateLimiter(), p, FIXED);
                Tool tool = tools(admission).get(i);
                tool.call(ARGS);
                String approvalId = audited.get(audited.size() - 1).approvalId();
                iterationApprovals.approve(approvalId, "approver-9", FIXED.instant());

                Tool other = variant.equals("profile") ? tools(admission, "STRICT").get(i) : tool;
                AuthenticatedCaller as = variant.equals("purpose") ? otherPurpose
                        : variant.equals("client") ? otherClient
                        : variant.equals("capabilities") ? moreCapabilities : CALLER;
                McpSchema.CallToolResult retried = other.call(as, ARGS);
                assertThat(retried.isError()).as(variant + " " + tool.name).isEqualTo(Boolean.TRUE);
                assertThat(text(retried)).as(variant + " " + tool.name)
                        .startsWith("APPROVAL_REQUIRED approvalId=").doesNotContain(approvalId);
                assertThat(orchestrator.requests).as(variant + " " + tool.name).isEmpty();

                // the approval was not consumed: the identical original call still succeeds once
                McpSchema.CallToolResult original = tool.call(ARGS);
                assertThat(original.isError()).as(variant + " " + tool.name).isNotEqualTo(Boolean.TRUE);
                assertThat(orchestrator.requests).hasSize(1);
                assertThat(audited.get(audited.size() - 1).approvalId()).isEqualTo(approvalId);
            }
        }
    }

    @Test
    @DisplayName("a successful result carries the correlationId in _meta only, never in structuredContent or text")
    void successCarriesCorrelationInMetaOnly() {
        for (Tool tool : tools(ToolAdmission.none())) {
            audited.clear();
            McpSchema.CallToolResult ok = tool.call(ARGS);
            String id = audited.get(audited.size() - 1).correlationId();
            assertThat(id).isNotBlank();
            assertThat(meta(ok)).isEqualTo(id);
            assertThat(text(ok)).doesNotContain(id);
            assertThat(String.valueOf(ok.structuredContent())).doesNotContain(id);
        }
    }

    @Test
    @DisplayName("an unauthenticated denial carries the correlationId written to its audit event")
    void unauthenticatedDenialCarriesCorrelation() {
        for (Tool tool : tools(ToolAdmission.none())) {
            audited.clear();
            McpSchema.CallToolResult result = tool.handler.apply(
                    new McpSyncServerExchange(new McpAsyncServerExchange(
                            "s", null, null, null, McpTransportContext.EMPTY)),
                    new McpSchema.CallToolRequest(tool.name, ARGS));
            assertThat(text(result)).isEqualTo("NO_AUTHENTICATED_CALLER");
            assertThat(meta(result)).isEqualTo(audited.get(0).correlationId());
        }
    }

    @Test
    @DisplayName("an authorisation denial carries the correlationId written to its audit event, on both tools")
    void authorisationDenialCarriesCorrelation() {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"), Map.of("investigator", Set.of()));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, ToolOptions.defaults().noAdmission().build());
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, ToolOptions.defaults().noAdmission().build());
        for (Tool tool : List.of(new Tool(GetEntityContextTool.NAME, get.specification().callHandler()),
                new Tool(CompareEntitySourcesTool.NAME, compare.specification().callHandler()))) {
            audited.clear();
            McpSchema.CallToolResult result = tool.call(ARGS);
            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            assertThat(meta(result)).as(tool.name).isEqualTo(audited.get(0).correlationId());
            assertThat(orchestrator.requests).isEmpty();
        }
    }

    @Test
    @DisplayName("a scope-resolution denial carries the correlationId written to its audit event, on both tools")
    void scopeResolutionDenialCarriesCorrelation() {
        AuthenticatedCaller unknownPurpose = new AuthenticatedCaller(
                "principal-1", "client-1", Set.of("investigator"), "not-a-purpose", "case-1", null);
        for (Tool tool : tools(ToolAdmission.none())) {
            audited.clear();
            McpSchema.CallToolResult result = tool.call(unknownPurpose, ARGS);
            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            assertThat(audited).as(tool.name).hasSize(1);
            assertThat(audited.get(0).policyDecision()).isNotEqualTo("ALLOW");
            assertThat(meta(result)).as(tool.name).isEqualTo(audited.get(0).correlationId());
            assertThat(orchestrator.requests).isEmpty();
        }
    }

    /** Audits an ALLOW event the way the real orchestrator does, copying the request's approval ids. */
    private static final class AuditingOrchestrator implements ContextOrchestrator {
        final List<ContextRequest> requests = new ArrayList<>();
        private final AuditRecorder audit;
        private final List<AuditEvent> sink;

        AuditingOrchestrator(AuditRecorder audit, List<AuditEvent> sink) {
            this.audit = audit;
            this.sink = sink;
        }

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            requests.add(request);
            String correlationId = java.util.UUID.randomUUID().toString();
            audit.record(new AuditEntry("principal-1", "client-1", request.toolName(), request.entityType(), "",
                    "", "", privacyContext.scopeId(), "demonstration", "case-1", "ALLOW", Set.of(),
                    request.rejectedArguments(), correlationId, Map.of(), request.approvalId(),
                    request.approverId()));
            return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(), List.of(),
                    DataPrismObjectMapper.create().createObjectNode(), Map.of(), correlationId);
        }
    }

    private static final class RecordingMetrics implements PrivacyMetrics {
        final List<Metric> incremented = new ArrayList<>();

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
    }
}
