package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.orchestration.*;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.*;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseLimitRefusalTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS = StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
    private record Parcel(String label) { }

    private McpSchema.CallToolResult call(ObjectNode tree, int records, int bytes, List<AuditEvent> events) {
        var adapter = new DataSourceAdapter<Parcel>() {
            public String sourceName() { return "synthetic-depot"; }
            public Class<Parcel> responseType() { return Parcel.class; }
            public Parcel fetch(DataRequest request) { return new Parcel("synthetic-parcel"); }
        };
        var recorder = new AuditRecorder(events::add, CLOCK, "test-response-limit");
        var orchestrator = new DefaultContextOrchestrator(List.of(adapter),
                (record, context) -> new ScrubResult(tree, Set.of()), new DefaultFieldMetadataResolver(),
                List.of((node, prohibited, emitted, context) -> ValidationResult.ok()),
                (subject, namespace, context) -> "SUBJ-1", new ParameterFingerprinter(KEYS), recorder,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), CLOCK, SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), new RequestLimits(8, records, bytes, Duration.ofSeconds(3), 4, 100),
                (raw, context) -> List.of(), new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
        var policy = new SecurityPolicy(Set.of("test"), Map.of("operator", Set.of("GET_ENTITY_CONTEXT")));
        var caller = new AuthenticatedCaller("principal-1", "client-1", Set.of("operator"), "test", "case-1", null);
        var tool = new GetEntityContextTool(orchestrator,
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.CASE),
                new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1, Duration.ofHours(1), new PurposeValidator(Set.of("test"))),
                PrivacyMetrics.none(), recorder, CLOCK, caller, ToolOptions.defaults().noAdmission().build());
        return tool.specification().callHandler().apply(null,
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME, Map.of("entityType", "PARCEL", "subjectId", "fixture-1")));
    }

    @Test void bothLimitErrorsCarryOnlyTheCodePathAndAuditedCorrelation() {
        ObjectNode tree = JsonNodeFactory.instance.objectNode().put("label", "withheld-sentinel-value");
        tree.putArray("items").addObject().put("label", "synthetic");
        for (String code : List.of("TOO_MANY_RECORDS", "RESPONSE_TOO_LARGE")) {
            List<AuditEvent> events = new ArrayList<>();
            var result = call(tree, code.equals("TOO_MANY_RECORDS") ? 1 : 10,
                    code.equals("RESPONSE_TOO_LARGE") ? 1 : 10000, events);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent)result.content().getFirst()).text();
            assertThat(text).isEqualTo("refused: " + code + " at $");
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.policyDecision()).isEqualTo("DENY:" + code);
                assertThat(result.meta().get(ToolCalls.CORRELATION_ID_META_KEY)).isEqualTo(event.correlationId());
            });
            assertThat(result.structuredContent()).isNull();
        }
    }

    @Test void acceptedBoundaryBoundsTheRealToolTextAndStructuredBody() {
        for (String value : List.of("", "ascii", "é漢字", "📦\u2028\u2029", "\"\\\n\u0000", "\ud800")) {
            ObjectNode tree = JsonNodeFactory.instance.objectNode().put(value, value);
            tree.putArray("empty");
            tree.putArray("nested").addObject().put("label", value);
            int low = 1;
            int high = 10000;
            while (low < high) {
                int middle = (low + high) / 2;
                if (Boolean.TRUE.equals(call(tree, 2, middle, new ArrayList<>()).isError())) low = middle + 1;
                else high = middle;
            }
            var result = call(tree, 2, low, new ArrayList<>());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = ((McpSchema.TextContent)result.content().getFirst()).text();
            int actual = text.getBytes(StandardCharsets.UTF_8).length;
            var mapper = DataPrismObjectMapper.create();
            assertThat(mapper.readTree(text)).isEqualTo(mapper.valueToTree(result.structuredContent()));
            assertThat(low).isBetween(actual, 3 * actual);
            assertThat(call(tree, 2, low - 1, new ArrayList<>()).isError()).isTrue();
        }
    }
}
