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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins a whole tool result as serialised by the production mapper
 * ({@link DataPrismObjectMapper#create()}, Jackson 2 defaults plus dates-as-text and
 * fail-on-empty-beans), for a fixed synthetic subject, a fixed clock and the shipped scope
 * rules. The compared text is the tool's text content. The structured-content golden pins the
 * convertValue-to-Map conversion of the result, re-serialised through {@link DataPrismObjectMapper};
 * it is not the MCP SDK's wire bytes.
 *
 * <p>Neither tool result contains a null or a date, so null inclusion and date format are NOT
 * pinned here. Dates are pinned only through the audit {@code timestamp} and the checkpoint
 * {@code recordedAt} and {@code segmentDate}.
 *
 * <p>One normalisation applies to get_entity_context: the entries of its {@code sources} object
 * are sorted before comparing (see {@link #sortSourcesObject}); their order varies between JVM runs.
 *
 * <p>The goldens record whatever Jackson 2 does for property order, null handling and instants;
 * the notes on each test say what that is.
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
                DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, FIXED, null,
                ToolOptions.defaults().noAdmission().build());
        return tool.specification().callHandler().apply(
                exchange(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY),
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME,
                        Map.of("entityType", "CUSTOMER", "subjectId", "123")));
    }

    private McpSchema.CallToolResult compareEntitySources() {
        var tool = new CompareEntitySourcesTool(assembly.orchestrator(), authorization, scopes,
                DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, FIXED, null,
                ToolOptions.defaults().noAdmission().build());
        return tool.specification().callHandler().apply(
                exchange(CompareEntitySourcesTool.TRANSPORT_CONTEXT_CALLER_KEY),
                new McpSchema.CallToolRequest(CompareEntitySourcesTool.NAME,
                        Map.of("entityType", "CUSTOMER", "subjectId", "123")));
    }

    /**
     * Sorts the entries of the {@code "sources"} object. The aliases and their values are
     * deterministic, but the order the tool writes them in differs from one JVM run to the
     * next (an immutable-map iteration order), so it cannot be pinned byte for byte. This is the
     * only normalisation; everything else is compared exactly. Aliases contain no comma or brace.
     */
    private static String sortSourcesObject(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"sources\":\\{([^}]*)\\}").matcher(json);
        if (!m.find()) {
            return json;
        }
        String sorted = java.util.Arrays.stream(m.group(1).split(",")).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return json.substring(0, m.start(1)) + sorted + json.substring(m.end(1));
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).hasSize(1);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static String structured(McpSchema.CallToolResult result) throws Exception {
        return DataPrismObjectMapper.create().writeValueAsString(result.structuredContent());
    }

    @Test
    @DisplayName("get_entity_context text content, byte for byte")
    void getEntityContextText() {
        Golden.assertMatches("get-entity-context.text.json", sortSourcesObject(text(getEntityContext())));
    }

    @Test
    @DisplayName("get_entity_context structured content through the production mapper, byte for byte")
    void getEntityContextStructured() throws Exception {
        Golden.assertMatches("get-entity-context.structured.json", sortSourcesObject(structured(getEntityContext())));
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
