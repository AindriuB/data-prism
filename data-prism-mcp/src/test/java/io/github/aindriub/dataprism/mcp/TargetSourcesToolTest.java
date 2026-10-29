package io.github.aindriub.dataprism.mcp;

import tools.jackson.databind.json.JsonMapper;
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
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code targetSources} on both tools: the schema, the argument parsing and the uniform refusal. */
class TargetSourcesToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-247-test-key-not-for-any-real-data-32b");
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);

    private record Thing(String id, String value) {
    }

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
    private final AtomicInteger asked = new AtomicInteger();
    private final List<ContextRequest> requests = new ArrayList<>();

    private PrivacyContext capturedScope;

    private final ContextOrchestrator recording = new ContextOrchestrator() {
        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext context,
                                            InvestigationContext investigationContext) {
            requests.add(request);
            capturedScope = context;
            return new ContextResponse("THING", "SUBJ-1", Map.of(), List.of(), Map.of(), "corr-1");
        }
    };

    private static DataSourceAdapter<Thing> source(String name, Set<String> types, AtomicInteger calls) {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public SourceDescription description() {
                return new SourceDescription(types, null, null, List.of(), null, null);
            }

            @Override
            public Thing fetch(DataRequest request) {
                calls.incrementAndGet();
                return new Thing("1", "raw");
            }
        };
    }

    private DefaultContextOrchestrator real() {
        var resolver = new DefaultFieldMetadataResolver();
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        return new DefaultContextOrchestrator(
                List.of(source("alpha", Set.of(), asked), source("gamma", Set.of(), asked),
                        source("delta", Set.of("OTHER_KIND"), asked)),
                (s, ctx) -> new ScrubResult(JsonMapper.builder().build().createObjectNode().put("value", "ok"),
                        Set.of()),
                resolver, List.of(ok, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS), audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none(),
                OrchestrationOptions.defaults().withVisibility((caller, name) -> !name.equals("gamma")));
    }

    private List<McpServerFeatures.SyncToolSpecification> specs(ContextOrchestrator orchestrator) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION,
                ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool get = new GetEntityContextTool(orchestrator, authz, scopes,
                PrivacyMetrics.none(), audit, FIXED, null, ToolOptions.defaults().noAdmission().build());
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(orchestrator, authz, scopes,
                PrivacyMetrics.none(), audit, FIXED, null, ToolOptions.defaults().noAdmission().build());
        return List.of(get.specification(), compare.specification());
    }

    private List<BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>> handlers(
            ContextOrchestrator orchestrator) {
        return specs(orchestrator).stream().map(McpServerFeatures.SyncToolSpecification::callHandler).toList();
    }

    private static McpSchema.CallToolResult call(
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
            String tool, Map<String, Object> arguments) {
        McpTransportContext context = McpTransportContext.create(
                Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, CALLER));
        return handler.apply(new McpSyncServerExchange(new McpAsyncServerExchange("s", null, null, null, context)),
                new McpSchema.CallToolRequest(tool, arguments));
    }

    private static Map<String, Object> args(Object targetSources) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("entityType", "THING");
        out.put("subjectId", "1");
        if (targetSources != null) {
            out.put("targetSources", targetSources);
        }
        return out;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static final String[] TOOLS = {GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME};

    @Test
    @DisplayName("both schemas declare targetSources: array, minItems 1, uniqueItems, items 1 to 64, no maxItems")
    @SuppressWarnings("unchecked")
    void schema() {
        for (McpServerFeatures.SyncToolSpecification spec : specs(recording)) {
            Map<String, Object> schema = spec.tool().inputSchema();
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            Map<String, Object> target = (Map<String, Object>) properties.get("targetSources");
            assertThat(target).containsEntry("type", "array").containsEntry("minItems", 1)
                    .containsEntry("uniqueItems", true).doesNotContainKey("maxItems");
            assertThat((Map<String, Object>) target.get("items"))
                    .containsEntry("type", "string").containsEntry("minLength", 1).containsEntry("maxLength", 64);
            assertThat((List<String>) schema.get("required")).containsExactly("entityType", "subjectId");
        }
    }

    @Test
    @DisplayName("labels reach the orchestrator in order, on both tools; absent labels stay absent")
    void labelsArePassedThrough() {
        var handlers = handlers(recording);
        for (int i = 0; i < 2; i++) {
            requests.clear();
            assertThat(call(handlers.get(i), TOOLS[i], args(List.of("one", "two"))).isError()).isNotEqualTo(true);
            assertThat(requests).singleElement()
                    .satisfies(r -> assertThat(r.targetSources()).contains(List.of("one", "two")));

            requests.clear();
            call(handlers.get(i), TOOLS[i], args(null));
            assertThat(requests).singleElement().satisfies(r -> assertThat(r.targetSources()).isEmpty());
        }
    }

    @Test
    @DisplayName("an empty array, a non-array, a duplicate, an empty or over-long label never reaches the orchestrator")
    void invalidArgumentsAreRejected() {
        var handlers = handlers(recording);
        List<Object> invalid = List.of(List.of(), "alpha", 7, List.of("a", "a"), List.of(""),
                List.of("x".repeat(65)), List.of(1), java.util.Arrays.asList("a", null));
        for (int i = 0; i < 2; i++) {
            for (Object bad : invalid) {
                McpSchema.CallToolResult result = call(handlers.get(i), TOOLS[i], args(bad));
                assertThat(result.isError()).as("%s %s", TOOLS[i], bad).isEqualTo(true);
                assertThat(text(result)).doesNotContain("alpha");
            }
        }
        assertThat(requests).isEmpty();
        assertThat(call(handlers.get(0), TOOLS[0], args(List.of("x".repeat(64)))).isError()).isNotEqualTo(true);
    }

    @Test
    @DisplayName("an unknown, wrong-kind, unpermitted and non-serving label give byte-identical client results "
            + "apart from the correlation id, and call nothing")
    void refusalsAreUniform() {
        // The scope a session resolves to is read from a first call, so the aliases below are the real ones.
        call(handlers(recording).get(0), TOOLS[0], args(null));
        PrivacyContext scope = capturedScope;
        SourceAliasing aliasing = new SourceAliasing(new HmacValueTokenSource(KEYS));
        var handlers = handlers(real());
        String[] labels = {"no-such-label", "alpha", aliasing.aliasFor("gamma", scope),
                aliasing.aliasFor("delta", scope)};
        String[] reasons = {"CATALOG_UNKNOWN_SOURCE", "CATALOG_UNKNOWN_SOURCE", "CATALOG_SOURCE_NOT_PERMITTED",
                "CATALOG_ENTITY_TYPE_MISMATCH"};

        for (int i = 0; i < 2; i++) {
            List<String> seen = new ArrayList<>();
            for (int c = 0; c < labels.length; c++) {
                audited.clear();
                McpSchema.CallToolResult result = call(handlers.get(i), TOOLS[i], args(List.of(labels[c])));
                assertThat(result.isError()).isEqualTo(true);
                seen.add(text(result));
                assertThat(audited).hasSize(1);
                assertThat(audited.get(0).policyDecision()).isEqualTo("DENY:SOURCE_NOT_AVAILABLE");
                assertThat(audited.get(0).fieldDispositions())
                        .containsEntry("catalog", "REFUSED:" + reasons[c]).containsEntry("names", "ALIAS");
                assertThat(audited.get(0).toString()).doesNotContain(labels[c]);
            }
            assertThat(seen).containsOnly("refused: SOURCE_NOT_AVAILABLE at $");
            assertThat(asked.get()).isZero();
        }
    }
}
