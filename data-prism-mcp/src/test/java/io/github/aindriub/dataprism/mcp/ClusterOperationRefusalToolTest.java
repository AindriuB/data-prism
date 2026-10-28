package io.github.aindriub.dataprism.mcp;

import tools.jackson.databind.json.JsonMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
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
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cluster that refuses an operation reaches the caller as {@code CLUSTER_OPERATION_REFUSED}, from admission and
 * from the read budget, like {@code CLUSTER_LOCK_TIMEOUT}; nothing is read and the denial is audited.
 */
class ClusterOperationRefusalToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-233-test-key-not-for-any-real-data-32b");
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final Map<String, Object> ARGS = Map.of("entityType", "THING", "subjectId", "1");
    private static final String SENTINEL = "sentinel-key-x-1";

    private record Thing(String id, String value) {
    }

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
    private final AtomicInteger fetches = new AtomicInteger();

    private DataSourceAdapter<Thing> source() {
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
                fetches.incrementAndGet();
                return new Thing("1", "raw");
            }
        };
    }

    private DefaultContextOrchestrator orchestrator(ScopeBudget budget) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(JsonMapper.builder().build().createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        return new DefaultContextOrchestrator(List.of(source()), scrubber, resolver,
                List.of(ok, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS), audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                budget, RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private McpSchema.CallToolResult call(DefaultContextOrchestrator orchestrator, ToolAdmission admission) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes, PrivacyMetrics.none(),
                audit, FIXED, null,
                ToolOptions.defaults().admission(admission, new ParameterFingerprinter(KEYS)).build());
        McpTransportContext context = McpTransportContext.create(
                Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, CALLER));
        return get.specification().callHandler().apply(
                new McpSyncServerExchange(new McpAsyncServerExchange("s", null, null, null, context)),
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME, ARGS));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static ScopeBudget refusingBudget() {
        return new ScopeBudget() {
            @Override
            public boolean tryRead(String scopeId, String subjectId, int budget) {
                throw new ClusterOperationRefusedException(new IllegalArgumentException(SENTINEL));
            }

            @Override
            public io.github.aindriub.dataprism.core.limits.ReadReservation reserve(String scopeId, String subjectId,
                    int reads, int subjectBudget, int scopeTotalBudget) {
                throw new ClusterOperationRefusedException(new IllegalArgumentException(SENTINEL));
            }

            @Override
            public int reads(String scopeId, String subjectId) {
                return 0;
            }

            @Override
            public void forget(String scopeId) {
            }
        };
    }

    @Test
    void anAdmissionStoreThatRefusesTheClusterOperationRefusesTheCallWithItsCode() {
        ApprovalStore refusing = (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, a) -> {
                    throw new ClusterOperationRefusedException(new IllegalArgumentException(SENTINEL));
                });
        OversightPolicy policy = new OversightPolicy(Set.of(GetEntityContextTool.NAME), OptionalInt.empty(),
                Duration.ofMinutes(1), Duration.ofHours(1));
        var admission = new ToolAdmission(new InMemoryOversightState(), refusing, new InMemoryCallerRateLimiter(),
                policy, FIXED);

        McpSchema.CallToolResult result = call(orchestrator(new InMemoryScopeBudget()),
                admission);

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("CLUSTER_OPERATION_REFUSED");
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).policyDecision()).isEqualTo("DENY:CLUSTER_OPERATION_REFUSED");
        assertThat(fetches).hasValue(0);
    }

    @Test
    void aReadBudgetThatRefusesTheClusterOperationRefusesTheReadWithItsCode() {
        McpSchema.CallToolResult result = call(orchestrator(refusingBudget()), ToolAdmission.none());

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("refused: CLUSTER_OPERATION_REFUSED at $");
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).policyDecision()).isEqualTo("DENY:CLUSTER_OPERATION_REFUSED");
        assertThat(audited.get(0).fieldDispositions()).containsEntry("merged:<refused>", "REFUSED");
        assertThat(audited.toString()).doesNotContain(SENTINEL);
        assertThat(fetches).hasValue(0);
    }
}
