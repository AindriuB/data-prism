package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.orchestration.AuditedRefusalException;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.security.AdmissionDecision;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationDecision;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A refusal code supplied by the application reaches the client only if it is a plain
 * upper-case token; anything else is shown as {@code INVALID_REFUSAL_CODE}, and the raw text
 * appears in no tool result and no log event.
 */
class MalformedRefusalCodeTest {

    private static final String MALFORMED = "leak alice@example.invalid +353-0-000-0000";
    private static final String LEAK = "example.invalid";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final Map<String, Object> ARGS = Map.of("entityType", "CUSTOMER", "subjectId", "123");

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
    private final PrivacyMetrics metrics = new PrivacyMetrics() {
        @Override
        public void increment(Metric metric) {
        }

        @Override
        public void increment(Metric metric, String sourceName) {
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
        }
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
    }

    /** Both tools, built around the given collaborators. */
    private Map<String, BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>> tools(
            AuthorizationService authz, ScopeResolver scopes, ToolAdmission admission, ContextOrchestrator orchestrator) {
        ParameterFingerprinter fingerprinter =
                new ParameterFingerprinter(StaticSecretKeyProvider.of("task-128-test-key-not-for-any-real-data-32b"));
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, ToolOptions.defaults().admission(admission, fingerprinter).build());
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(orchestrator, authz, scopes,
                DataPrismObjectMapper.create(), metrics, audit, FIXED, null, ToolOptions.defaults().admission(admission, fingerprinter).build());
        return Map.of(
                GetEntityContextTool.NAME, get.specification().callHandler(),
                CompareEntitySourcesTool.NAME, compare.specification().callHandler());
    }

    private static AuthorizationService allowingAuthz() {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        return new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
    }

    private static ScopeResolver realScopes() {
        return new ScopeResolver(VERSION, Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")));
    }

    private static ContextOrchestrator unreachable() {
        return (request, privacy, investigation) -> {
            throw new AssertionError("the orchestrator must not be reached");
        };
    }

    private static McpSyncServerExchange exchange() {
        McpTransportContext context = McpTransportContext.create(
                Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, CALLER));
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null, context));
    }

    private void assertScrubbed(
            Map<String, BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>> tools) {
        assertThat(tools).hasSize(2);
        tools.forEach((name, handler) -> {
            logs.list.clear();
            McpSchema.CallToolResult result = handler.apply(exchange(), new McpSchema.CallToolRequest(name, ARGS));
            assertThat(result.isError()).as(name).isEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).as(name)
                    .contains("INVALID_REFUSAL_CODE");
            assertThat(String.valueOf(result.content())).as(name).doesNotContain(LEAK);
            assertThat(String.valueOf(result.meta())).as(name).doesNotContain(LEAK);
            assertThat(String.valueOf(result.structuredContent())).as(name).doesNotContain(LEAK);
            for (ILoggingEvent event : logs.list) {
                assertThat(event.getFormattedMessage()).as(name + " log").doesNotContain(LEAK);
                assertThat(String.valueOf(event.getThrowableProxy())).as(name + " log").doesNotContain(LEAK);
            }
        });
    }

    @Test
    @DisplayName("an authorisation denial code that is not a token is shown as INVALID_REFUSAL_CODE")
    void denialCode() {
        AuthorizationService authz = mock(AuthorizationService.class);
        when(authz.authorize(any(), any())).thenReturn(AuthorizationDecision.denied(MALFORMED));
        assertScrubbed(tools(authz, realScopes(), ToolAdmission.none(), unreachable()));
    }

    @Test
    @DisplayName("a scope-resolution refusal code that is not a token is shown as INVALID_REFUSAL_CODE")
    void securityRefusedCode() {
        ScopeResolver scopes = mock(ScopeResolver.class);
        when(scopes.resolve(any(), any(), any())).thenThrow(new SecurityRefusedException(MALFORMED, "detail"));
        assertScrubbed(tools(allowingAuthz(), scopes, ToolAdmission.none(), unreachable()));
    }

    @Test
    @DisplayName("an admission refusal code that is not a token is shown as INVALID_REFUSAL_CODE")
    void admissionCode() {
        ToolAdmission admission = mock(ToolAdmission.class);
        when(admission.admit(any(), any(), any(), any())).thenReturn(AdmissionDecision.refuse(MALFORMED));
        assertScrubbed(tools(allowingAuthz(), realScopes(), admission, unreachable()));
    }

    @Test
    @DisplayName("a PrivacyRefusedException code that is not a token is shown as INVALID_REFUSAL_CODE")
    void privacyRefusedCode() {
        ContextOrchestrator orchestrator = (request, privacy, investigation) -> {
            throw new PrivacyRefusedException(MALFORMED, "CUSTOMER", "detail");
        };
        assertScrubbed(tools(allowingAuthz(), realScopes(), ToolAdmission.none(), orchestrator));
    }

    @Test
    @DisplayName("an audited refusal code that is not a token is shown as INVALID_REFUSAL_CODE")
    void auditedRefusalCode() {
        AuditedRefusalException refused = mock(AuditedRefusalException.class);
        when(refused.code()).thenReturn(MALFORMED);
        when(refused.path()).thenReturn("CUSTOMER");
        when(refused.correlationId()).thenReturn("corr-1");
        ContextOrchestrator orchestrator = (request, privacy, investigation) -> {
            throw refused;
        };
        assertScrubbed(tools(allowingAuthz(), realScopes(), ToolAdmission.none(), orchestrator));
    }

    @Test
    @DisplayName("a well-formed code is returned unchanged")
    void wellFormedCodeUnchanged() {
        AuthorizationService authz = mock(AuthorizationService.class);
        when(authz.authorize(any(), any())).thenReturn(AuthorizationDecision.denied("TOOL_PAUSED"));
        tools(authz, realScopes(), ToolAdmission.none(), unreachable()).forEach((name, handler) -> {
            McpSchema.CallToolResult result = handler.apply(exchange(), new McpSchema.CallToolRequest(name, ARGS));
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).as(name).isEqualTo("TOOL_PAUSED");
        });
    }
}
