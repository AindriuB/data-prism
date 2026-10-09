package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.example.StubAccountAdapter;
import io.github.aindriub.dataprism.example.StubCustomerAdapter;
import io.github.aindriub.dataprism.example.StubOrderAdapter;
import io.github.aindriub.dataprism.mcp.CompareEntitySourcesTool;
import tools.jackson.databind.json.JsonMapper;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins a whole tool result for a fixed synthetic subject, a fixed clock and the shipped scope
 * rules. The compared text is the tool's text content, which the tool writes with the production
 * mapper ({@code DataPrismObjectMapper}), so the text goldens pin that mapper's configuration.
 * The structured-content golden pins the convertValue-to-Map conversion the tool performs with the
 * same mapper. That map holds only plain JSON values (see
 * {@link #structuredContentIsPlainJsonAndAgreesWithTheText}), so the test-local mapper that
 * writes it out adds no configuration of its own; it is not the MCP SDK's wire bytes.
 *
 * <p>Neither tool result contains a null or a date, so null inclusion and date format are NOT
 * pinned here. Dates are pinned only through the audit {@code timestamp} and the checkpoint
 * {@code recordedAt} and {@code segmentDate}.
 *
 * <p>The {@code sources} entries of get_entity_context are pinned in ascending key order (the
 * order {@code ContextResponse} guarantees), which is the same on every JVM run.
 *
 * <p>The goldens record what the production mapper does for property order, null handling and
 * instants; the notes on each test say what that is.
 */
class ToolResultCharacterisationTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
    private static final String PURPOSE = "demonstration";
    private static final String ROLE = "investigator";

    private final DataPrismAssembly assembly = new DataPrismAssembly(
            List.of(new StubCustomerAdapter(), new StubAccountAdapter(), new StubOrderAdapter()), FIXED, event -> { });
    private final AuditRecorder toolAudit = new AuditRecorder(event -> { }, FIXED, "characterisation-1");
    private final AuthorizationService authorization = new AuthorizationService(
            new SecurityPolicy(Set.of(PURPOSE), Map.of(ROLE,
                    Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES))),
            "DEFAULT", PrivacyScopeType.INVESTIGATION);
    private final ScopeResolver scopes = new ScopeResolver(assembly.pseudonymisationVersion(),
            Duration.ofHours(8), new PurposeValidator(Set.of(PURPOSE)));

    private static McpSyncServerExchange exchange(String callerKey) {
        AuthenticatedCaller caller =
                new AuthenticatedCaller("investigator-1", "client-1", Set.of(ROLE), PURPOSE, "case-1", null);
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null,
                McpTransportContext.create(Map.of(callerKey, caller))));
    }

    private McpSchema.CallToolResult getEntityContext() {
        var tool = new GetEntityContextTool(assembly.orchestrator(), authorization, scopes,
                PrivacyMetrics.none(), toolAudit, FIXED, null,
                ToolOptions.defaults().noAdmission().build());
        return tool.specification().callHandler().apply(
                exchange(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY),
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME,
                        Map.of("entityType", "CUSTOMER", "subjectId", "123")));
    }

    private McpSchema.CallToolResult compareEntitySources() {
        var tool = new CompareEntitySourcesTool(assembly.orchestrator(), authorization, scopes,
                PrivacyMetrics.none(), toolAudit, FIXED, null,
                ToolOptions.defaults().noAdmission().build());
        return tool.specification().callHandler().apply(
                exchange(CompareEntitySourcesTool.TRANSPORT_CONTEXT_CALLER_KEY),
                new McpSchema.CallToolRequest(CompareEntitySourcesTool.NAME,
                        Map.of("entityType", "CUSTOMER", "subjectId", "123")));
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).hasSize(1);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static String structured(McpSchema.CallToolResult result) throws Exception {
        return JsonMapper.builder().build().writeValueAsString(result.structuredContent());
    }

    @Test
    @DisplayName("structured content holds only plain JSON values and is the same document as the text content")
    void structuredContentIsPlainJsonAndAgreesWithTheText() throws Exception {
        for (McpSchema.CallToolResult result : List.of(getEntityContext(), compareEntitySources())) {
            assertPlainJson(result.structuredContent());
            var mapper = JsonMapper.builder().build();
            tools.jackson.databind.JsonNode structured = mapper.valueToTree(result.structuredContent());
            assertThat((Object) structured).isEqualTo(mapper.readTree(text(result)));
        }
    }

    private static void assertPlainJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                assertThat((Object) k).isInstanceOf(String.class);
                assertPlainJson(v);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(ToolResultCharacterisationTest::assertPlainJson);
        } else {
            assertThat(value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean).as("plain JSON value: %s", value).isTrue();
        }
    }

    @Test
    @DisplayName("get_entity_context text content, byte for byte")
    void getEntityContextText() {
        Golden.assertMatches("get-entity-context.text.json", text(getEntityContext()));
    }

    @Test
    @DisplayName("get_entity_context structured content through the production mapper, byte for byte")
    void getEntityContextStructured() throws Exception {
        Golden.assertMatches("get-entity-context.structured.json", structured(getEntityContext()));
    }

    @Test
    @DisplayName("compare_entity_sources text content, byte for byte")
    void compareEntitySourcesText() {
        Golden.assertMatches("compare-entity-sources.text.json", text(compareEntitySources()));
    }

    @Test
    @DisplayName("compare_entity_sources structured content through the production mapper, byte for byte")
    void compareEntitySourcesStructured() throws Exception {
        Golden.assertMatches("compare-entity-sources.structured.json", structured(compareEntitySources()));
    }
}
