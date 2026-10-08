package io.github.aindriub.dataprism.example.http;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.example.StubAccountAdapter;
import io.github.aindriub.dataprism.example.StubCustomerAdapter;
import io.github.aindriub.dataprism.example.StubOrderAdapter;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.mcp.ToolOptions;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real {@code get_entity_context} call, written through {@link FileAuditSink}:
 * the record is version 2, carries field dispositions (paths and actions), and
 * none of the stub fixtures' identifying string values appears anywhere in the file.
 */
class AuditFieldDispositionTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an ALLOW record is version 2, lists field dispositions, and leaks no fixture value")
    void allowRecordCarriesDispositionsAndNoValues(@TempDir Path tempDir) throws IOException {
        Path auditFile = tempDir.resolve("audit.log");
        String purpose = "demonstration";
        String role = "investigator";

        try (FileAuditSink fileSink = new FileAuditSink(auditFile)) {
            DataPrismAssembly assembly = new DataPrismAssembly(
                    List.of(new StubCustomerAdapter(), new StubAccountAdapter(), new StubOrderAdapter()),
                    FIXED_CLOCK, fileSink);
            AuditRecorder toolAudit = new AuditRecorder(fileSink, FIXED_CLOCK, "disposition-mcp");
            SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                    Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT)));
            AuthorizationService authorizationService =
                    new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
            ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(),
                    Duration.ofHours(8), new PurposeValidator(Set.of(purpose)));
            GetEntityContextTool tool = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                    scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, FIXED_CLOCK, null, ToolOptions.defaults().noAdmission().build());
            AuthenticatedCaller caller = new AuthenticatedCaller(
                    "disposition-principal", "disposition-client", Set.of(role), purpose, "CASE-DISP-1", null);
            McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                    "disposition-session", null, null, null,
                    McpTransportContext.create(
                            Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));

            tool.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                    GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));
        }

        String file = Files.readString(auditFile, StandardCharsets.UTF_8);
        List<AuditEvent> allows = file.lines().map(AuditRecordFormat::parse)
                .filter(event -> "ALLOW".equals(event.policyDecision())).toList();

        assertThat(allows).hasSize(1);
        assertThat(allows.get(0).recordVersion()).isEqualTo(AuditEvent.CURRENT_VERSION);
        assertThat(allows.get(0).fieldDispositions()).isNotEmpty();

        List<String> leaked = new ArrayList<>();
        for (String banned : identifyingStrings()) {
            if (file.contains(banned)) {
                leaked.add(banned);
            }
        }
        assertThat(leaked).isEmpty();
    }

    /** The distinctive undeclared key; example.com is a reserved domain. */
    private static final String PAYLOAD_KEY = "leaky.key@example.com";

    /** A payload whose only property is undeclared, and whose name is itself identifying. */
    @LlmExposedModel
    public static final class KeyedPayload {
        @JsonAnyGetter
        public Map<String, Object> extras() {
            return Map.of(PAYLOAD_KEY, 1);
        }
    }

    @Test
    @DisplayName("a DENY from an undeclared payload key records a fixed REFUSED key, never the payload key")
    void denyDoesNotRecordPayloadKeys(@TempDir Path tempDir) throws IOException {
        Path auditFile = tempDir.resolve("audit.log");
        String purpose = "demonstration";
        String role = "investigator";
        DataSourceAdapter<KeyedPayload> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "keyed-api";
            }

            @Override
            public Class<KeyedPayload> responseType() {
                return KeyedPayload.class;
            }

            @Override
            public KeyedPayload fetch(DataRequest request) {
                return new KeyedPayload();
            }
        };

        try (FileAuditSink fileSink = new FileAuditSink(auditFile)) {
            DataPrismAssembly assembly = new DataPrismAssembly(List.of(adapter), FIXED_CLOCK, fileSink);
            AuditRecorder toolAudit = new AuditRecorder(fileSink, FIXED_CLOCK, "disposition-mcp");
            SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                    Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT)));
            AuthorizationService authorizationService =
                    new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
            ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(),
                    Duration.ofHours(8), new PurposeValidator(Set.of(purpose)));
            GetEntityContextTool tool = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                    scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, FIXED_CLOCK, null, ToolOptions.defaults().noAdmission().build());
            AuthenticatedCaller caller = new AuthenticatedCaller(
                    "disposition-principal", "disposition-client", Set.of(role), purpose, "CASE-DISP-2", null);
            McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                    "disposition-session", null, null, null,
                    McpTransportContext.create(
                            Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));

            tool.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                    GetEntityContextTool.NAME, Map.of("entityType", "PAYLOAD", "subjectId", "123")));
        }

        String file = Files.readString(auditFile, StandardCharsets.UTF_8);
        List<AuditEvent> denies = file.lines().map(AuditRecordFormat::parse)
                .filter(event -> "DENY:UNKNOWN_FIELD".equals(event.policyDecision())
                        && !event.fieldDispositions().isEmpty()).toList();

        assertThat(denies).hasSize(1);
        assertThat(denies.get(0).fieldDispositions()).containsEntry("keyed-api:<refused>", "REFUSED");
        assertThat(file).doesNotContain(PAYLOAD_KEY).doesNotContain("leaky.key");
    }

    /** The stub fixtures' own string leaves of six or more characters, derived rather than listed. */
    private static Set<String> identifyingStrings() {
        Set<String> values = new LinkedHashSet<>();
        List<Collection<? extends Record>> fixtures = List.of(StubCustomerAdapter.fixtureRecords(),
                StubAccountAdapter.fixtureRecords(), StubOrderAdapter.fixtureRecords());
        fixtures.forEach(records -> records.forEach(r -> collect(values, r)));
        assertThat(values).as("the derivation must not be vacuous").isNotEmpty();
        return values;
    }

    private static void collect(Set<String> values, Object value) {
        if (value instanceof String s) {
            if (s.length() >= 6) {
                values.add(s);
            }
        } else if (value instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    collect(values, component.getAccessor().invoke(record));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(element -> collect(values, element));
        }
    }
}
