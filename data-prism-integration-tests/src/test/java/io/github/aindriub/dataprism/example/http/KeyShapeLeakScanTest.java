package io.github.aindriub.dataprism.example.http;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.sink.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.mcp.ToolOptions;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

/**
 * Under a Java-built PASS_THROUGH_UNSAFE profile a map key reaches the response
 * verbatim. The leak validators scan property names as well as values (task
 * 194), so a map keyed by an email-shaped string is refused: the whole call when
 * it is the only source, that source alone when another one can still answer.
 * The key is synthetic, at a reserved domain.
 */
class KeyShapeLeakScanTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    private static final String TOKEN = "zzKeyShapeScanRm3";
    private static final String KEY = TOKEN + "@example.com";
    private static final String OTHER_MARKER = "other-source-marker-77";

    @LlmExposedModel
    record Keyed(@InternalIdentifier String id,
                 @NonSensitive(reason = "carrier for undeclared properties; none are declared") @JsonAnyGetter
                 Map<String, Object> extra) {
    }

    @LlmExposedModel
    record Plain(@InternalIdentifier String id,
                 @NonSensitive(reason = "fixed marker proving this source was returned") String marker) {
    }

    private static DataSourceAdapter<Keyed> keyed() {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "keyed-api";
            }

            @Override
            public Class<Keyed> responseType() {
                return Keyed.class;
            }

            @Override
            public Keyed fetch(DataRequest request) {
                return new Keyed("123", Map.of(KEY, "benign-note"));
            }
        };
    }

    private static DataSourceAdapter<Plain> plain() {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "plain-api";
            }

            @Override
            public Class<Plain> responseType() {
                return Plain.class;
            }

            @Override
            public Plain fetch(DataRequest request) {
                return new Plain("123", OTHER_MARKER);
            }
        };
    }

    private record Run(McpSchema.CallToolResult result, String log, List<AuditEvent> events) {

        String surface() {
            StringBuilder out = new StringBuilder();
            try {
                out.append(JsonMapper.builder().build().writeValueAsString(result.structuredContent()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            for (McpSchema.Content c : result.content()) {
                if (c instanceof McpSchema.TextContent text) {
                    out.append(text.text());
                }
            }
            return out.toString();
        }
    }

    private static Run run(List<DataSourceAdapter<?>> adapters) {
        List<AuditEvent> events = new ArrayList<>();
        AuditSink recording = event -> {
            events.add(event);
            new Slf4jAuditSink().record(event);
        };
        McpSchema.CallToolResult[] result = new McpSchema.CallToolResult[1];
        String log = capture(() -> result[0] = call(adapters, recording));
        return new Run(result[0], log, events);
    }

    private static McpSchema.CallToolResult call(List<DataSourceAdapter<?>> adapters, AuditSink sink) {
        String purpose = "demonstration";
        String role = "investigator";
        Map<String, PrivacyProfile> profiles = Map.of("DEFAULT", new PrivacyProfile("DEFAULT",
                PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT))));
        DataPrismAssembly assembly = new DataPrismAssembly(adapters, CLOCK, sink, "DEFAULT", "en", profiles);
        SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT)));
        AuthorizationService authorization =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool get = new GetEntityContextTool(assembly.orchestrator(), authorization, scopes,
                PrivacyMetrics.none(), new AuditRecorder(sink, CLOCK, "key-shape-mcp"), CLOCK, null,
                ToolOptions.defaults().noAdmission().build());
        AuthenticatedCaller caller = new AuthenticatedCaller(
                "key-shape-principal", "key-shape-client", Set.of(role), purpose, "CASE-KS-1", null);
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                "key-shape-session", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));
        return get.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));
    }

    private static String capture(Runnable action) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream redirect = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        System.setOut(redirect);
        System.setErr(redirect);
        try {
            action.run();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void assertKeyAbsent(Run run) {
        assertThat(run.surface()).doesNotContain(TOKEN);
        assertThat(run.log()).as("log").contains("event=").doesNotContain(TOKEN);
        assertThat(run.events()).as("audit events").isNotEmpty();
        assertThat(run.events().toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("one source keyed by an email-shaped string: the call is refused VALIDATION_FAILED")
    void singleSourceRefused() {
        Run run = run(List.of(keyed()));

        assertThat(run.result().isError()).isTrue();
        assertThat(run.surface()).contains("VALIDATION_FAILED");
        assertKeyAbsent(run);
    }

    @Test
    @DisplayName("two sources: the keyed one is REFUSED and absent, the other is returned")
    void twoSourcesRefuseOnlyTheKeyedOne() {
        Run run = run(List.of(keyed(), plain()));

        assertThat(run.result().isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(run.surface()).contains(OTHER_MARKER).contains("\"status\":\"REFUSED\"");
        assertThat(run.events().toString()).contains("REFUSED");
        assertKeyAbsent(run);
    }
}
