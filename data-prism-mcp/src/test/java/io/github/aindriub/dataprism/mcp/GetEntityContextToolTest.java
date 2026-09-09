package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
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

/**
 * Drives {@link GetEntityContextTool#specification()}'s call handler directly,
 * the way the SDK does: with a real {@link McpSyncServerExchange} carrying a
 * transport context, never a supplier.
 */
class GetEntityContextToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;
    private final AuditRecorder audit = new AuditRecorder(sink, FIXED, "test-mcp");
    private final RecordingMetrics metrics = new RecordingMetrics();

    private static AuthenticatedCaller caller(String caseId, String purpose) {
        return new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), purpose, caseId, null);
    }

    private static SecurityPolicy policyGrantingGetEntityContext() {
        return new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT")));
    }

    private static AuthorizationService authorizationService(SecurityPolicy policy) {
        return new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
    }

    private static ScopeResolver scopeResolver(Set<String> allowedPurposes) {
        return new ScopeResolver(VERSION, Duration.ofHours(8), new PurposeValidator(allowedPurposes));
    }

    private static McpSyncServerExchange exchangeFor(AuthenticatedCaller caller) {
        McpTransportContext context = caller == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller));
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null, context));
    }

    private static McpSchema.CallToolRequest request(Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(GetEntityContextTool.NAME, arguments);
    }

    private static String soleText(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    @DisplayName("a caller holding GET_ENTITY_CONTEXT reaches the orchestrator and MCP_REQUESTS is incremented")
    void authorisedCallerReachesOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationService(policyGrantingGetEntityContext()), scopeResolver(Set.of("demonstration")),
                DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller("case-1", "demonstration")),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(orchestrator.requests).hasSize(1);
        assertThat(metrics.incremented).containsExactly(Metric.MCP_REQUESTS);
    }

    @Test
    @DisplayName("a caller without GET_ENTITY_CONTEXT is denied before reaching the orchestrator")
    void unauthorisedCallerIsDeniedBeforeOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        SecurityPolicy policyMissingCapability = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("COMPARE_ENTITY_SOURCES")));
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationService(policyMissingCapability), scopeResolver(Set.of("demonstration")),
                DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller("case-1", "demonstration")),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo("TOOL_NOT_PERMITTED");
        assertThat(orchestrator.requests).isEmpty();
        assertThat(metrics.incremented).containsExactly(Metric.MCP_DENIED);
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("TOOL_NOT_PERMITTED");
            assertThat(event.principalId()).isEqualTo("principal-1");
        });
    }

    @Test
    @DisplayName("a call with no caller in the transport context is refused and never reaches the orchestrator")
    void missingCallerNeverReachesOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationService(policyGrantingGetEntityContext()), scopeResolver(Set.of("demonstration")),
                DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(null), request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo(GetEntityContextTool.NO_AUTHENTICATED_CALLER);
        assertThat(orchestrator.requests).isEmpty();
    }

    @Test
    @DisplayName("a caller whose purpose the resolver refuses is denied with the resolver's code")
    void resolverRefusalIsDeniedWithItsCode() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationService(policyGrantingGetEntityContext()), scopeResolver(Set.of("demonstration")),
                DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller("case-1", "not-an-allowed-purpose")),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo(PurposeValidator.UNKNOWN_PURPOSE);
        assertThat(orchestrator.requests).isEmpty();
        assertThat(metrics.incremented).containsExactly(Metric.MCP_DENIED);
        assertThat(audited).singleElement()
                .satisfies(event -> assertThat(event.policyDecision()).isEqualTo(PurposeValidator.UNKNOWN_PURPOSE));
    }

    @Test
    @DisplayName("reserved argument names are ignored and reported, never read for their value")
    void reservedArgumentsAreIgnoredAndReported() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationService(policyGrantingGetEntityContext()), scopeResolver(Set.of("demonstration")),
                DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller("case-1", "demonstration")),
                request(Map.of(
                        "entityType", "CUSTOMER", "subjectId", "123",
                        "scopeId", "CASE-SOMEONE-ELSE", "purpose", "whatever",
                        "principalId", "someone-else", "caseId", "CASE-SOMEONE-ELSE")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(orchestrator.requests).singleElement().satisfies(req ->
                assertThat(req.rejectedArguments())
                        .containsExactlyInAnyOrder("scopeId", "purpose", "principalId", "caseId"));
        // Served from the session, not the (rejected) supplied caseId.
        assertThat(orchestrator.investigationContexts).singleElement()
                .satisfies(ic -> assertThat(ic.caseId()).isEqualTo("case-1"));
    }

    @Test
    @DisplayName("two callers with different case_id claims read the same subject as two different pseudonyms")
    void differentCaseIdsProduceDifferentPseudonymsAtTheTool() {
        var vocabulary = VocabularyRegistry.withBuiltIns().resolve("en");
        SyntheticValueSource synthetics = new HmacSyntheticGenerator(
                StaticSecretKeyProvider.of("test-only-key-not-for-any-real-data"), vocabulary);
        PseudonymEchoingOrchestrator orchestrator = new PseudonymEchoingOrchestrator(synthetics);
        ScopeResolver resolverPinnedToVocabulary = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withVocabulary(vocabulary.id()),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")));
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationService(policyGrantingGetEntityContext()), resolverPinnedToVocabulary,
                DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult first = tool.specification().callHandler().apply(
                exchangeFor(caller("case-1", "demonstration")),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "same-subject")));
        McpSchema.CallToolResult second = tool.specification().callHandler().apply(
                exchangeFor(caller("case-2", "demonstration")),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "same-subject")));

        assertThat(first.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(second.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(soleText(first)).isNotEqualTo(soleText(second));
    }

    /** Records every call it receives; never fabricates a response for a call it never got. */
    private static final class RecordingOrchestrator implements ContextOrchestrator {
        final List<ContextRequest> requests = new ArrayList<>();
        final List<InvestigationContext> investigationContexts = new ArrayList<>();

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            requests.add(request);
            investigationContexts.add(investigationContext);
            return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(), List.of(),
                    DataPrismObjectMapper.create().createObjectNode());
        }
    }

    /** Echoes the resolved scope's own synthetic value for the subject, nothing more. */
    private static final class PseudonymEchoingOrchestrator implements ContextOrchestrator {
        private final SyntheticValueSource synthetics;

        PseudonymEchoingOrchestrator(SyntheticValueSource synthetics) {
            this.synthetics = synthetics;
        }

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            String subject = synthetics.syntheticValue(request.subjectId(), PrivacyNamespace.NONE, privacyContext);
            return new ContextResponse(request.entityType(), subject, Map.of(), List.of(),
                    DataPrismObjectMapper.create().createObjectNode());
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
