package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
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

import static org.assertj.core.api.Assertions.assertThat;

/** A client outside the allowlist is audited as DENY:CLIENT_NOT_ALLOWED and the orchestrator is never reached. */
class ClientAllowlistDenialAuditTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
    private final List<ContextRequest> orchestratorCalls = new ArrayList<>();
    private final ContextOrchestrator orchestrator = (ContextRequest request, PrivacyContext privacy,
                                                      InvestigationContext investigation) -> {
        orchestratorCalls.add(request);
        return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(), List.of(), Map.of(), "");
    };

    @Test
    @DisplayName("a client outside the allowlist is audited as DENY:CLIENT_NOT_ALLOWED and nothing is read")
    void clientNotAllowed() {
        AuthenticatedCaller caller = new AuthenticatedCaller(
                "principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
        AuthorizationService authorization = new AuthorizationService(
                new SecurityPolicy(Set.of("demonstration"), Map.of("investigator", Set.of("GET_ENTITY_CONTEXT"))),
                "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.of(Set.of("other-client")));
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator, authorization, scopes,
                PrivacyMetrics.none(), audit, FIXED, null, ToolOptions.defaults().noAdmission().build());
        McpTransportContext context = McpTransportContext.create(
                Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller));
        McpSyncServerExchange exchange =
                new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null, context));

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(exchange,
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME,
                        Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(orchestratorCalls).isEmpty();
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("DENY:CLIENT_NOT_ALLOWED");
            assertThat(event.caseId()).isEqualTo("case-1");
            assertThat(event.clientId()).isEqualTo("client-1");
        });
    }
}
