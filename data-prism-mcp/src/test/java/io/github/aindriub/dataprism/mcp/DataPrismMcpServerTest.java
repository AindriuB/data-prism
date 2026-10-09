package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DataPrismMcpServer}'s two factories: stdio's single-principal refusal
 * rules, and streamable HTTP's confinement of the SDK's servlet type to one
 * builder call.
 */
class DataPrismMcpServerTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static AuthenticatedCaller developmentCaller(Set<String> extraRoles) {
        Set<String> roles = new java.util.LinkedHashSet<>(Set.of("developer"));
        roles.addAll(extraRoles);
        return new AuthenticatedCaller(
                "stdio-development", "stdio-development", roles, "demonstration", "CASE-DEMO-1", null);
    }

    private static AuthorizationService authorizationServiceGranting(Set<String> capabilities) {
        SecurityPolicy policy = new SecurityPolicy(Set.of("demonstration"), Map.of("developer", capabilities));
        return new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
    }

    private static ScopeResolver scopeResolver() {
        return new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
    }

    private static AuditRecorder audit() {
        AuditSink sink = event -> { };
        return new AuditRecorder(sink, FIXED, "test-mcp-server");
    }

    @Test
    @DisplayName("stdio refuses to start when singlePrincipalDevelopmentMode is not explicitly true")
    void stdioRefusesWithoutExplicitDevelopmentMode() {
        assertThatThrownBy(() -> DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), false, false, PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission().build()))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo("STDIO_DEVELOPMENT_ONLY"));
    }

    @Test
    @DisplayName("stdio refuses to start when the deployment says it is production, even in development mode")
    void stdioRefusesWhenProductionIsSet() {
        assertThatThrownBy(() -> DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), true, true, PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission().build()))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo("STDIO_DEVELOPMENT_ONLY"));
    }

    @Test
    @DisplayName("stdio starts when singlePrincipalDevelopmentMode is explicitly true and production is not set")
    void stdioStartsWithExplicitDevelopmentMode() {
        McpSyncServer server = DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), true, false, PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission().build());
        try {
            assertThat(server.listTools()).extracting(McpSchema.Tool::name)
                    .containsExactly(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME);
        } finally {
            server.closeGracefully();
        }
    }

    @Test
    @DisplayName("the transport's JSON mapper and both tools share one mapper instance")
    void transportAndBothToolsShareOneMapper() throws Exception {
        DataPrismMcpServer.Wiring wiring = DataPrismMcpServer.Wiring.of(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                PrivacyMetrics.none(), audit(), FIXED, developmentCaller(Set.of()),
                ToolOptions.defaults().noAdmission().build());

        assertThat(mapperOf(wiring.get())).isSameAs(wiring.mapper());
        assertThat(mapperOf(wiring.compare())).isSameAs(wiring.mapper());
        assertThat(((io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper) wiring.json()).getJsonMapper())
                .isSameAs(wiring.mapper());
    }

    @Test
    @DisplayName("the server itself, on either transport, uses the shared mapper rather than the SDK default")
    void serverUsesTheSharedMapper() throws Exception {
        var stdioWiring = wiringForTest();
        McpSyncServer stdio = DataPrismMcpServer.syncServer(stdioWiring,
                new io.modelcontextprotocol.server.transport.StdioServerTransportProvider(stdioWiring.json()));
        var httpWiring = wiringForTest();
        McpSyncServer http = DataPrismMcpServer.syncServer(httpWiring,
                io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(httpWiring.json()).contextExtractor(request -> McpTransportContext.EMPTY)
                        .mcpEndpoint("/mcp").build());
        try {
            assertThat(serverMapper(stdio)).isSameAs(stdioWiring.json());
            assertThat(serverMapper(http)).isSameAs(httpWiring.json());
        } finally {
            stdio.closeGracefully();
            http.closeGracefully();
        }
    }

    private static DataPrismMcpServer.Wiring wiringForTest() {
        return DataPrismMcpServer.Wiring.of(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                PrivacyMetrics.none(), audit(), FIXED, developmentCaller(Set.of()),
                ToolOptions.defaults().noAdmission().build());
    }

    /**
     * Reads private fields of the MCP Java SDK as of {@code mcp.version} 2.0.1:
     * {@code McpSyncServer.asyncServer}, then the {@code jsonMapper} field of that async server.
     * The SDK exposes no accessor for the mapper. Revisit this test whenever {@code mcp.version}
     * changes; a renamed field fails here with {@code NoSuchFieldException}.
     */
    private static Object serverMapper(McpSyncServer server) throws Exception {
        var sync = McpSyncServer.class.getDeclaredField("asyncServer");
        sync.setAccessible(true);
        Object async = sync.get(server);
        var mapper = async.getClass().getDeclaredField("jsonMapper");
        mapper.setAccessible(true);
        return mapper.get(async);
    }

    private static Object mapperOf(Object tool) throws Exception {
        var field = tool.getClass().getDeclaredField("mapper");
        field.setAccessible(true);
        return field.get(tool);
    }

    @Test
    @DisplayName("the stdio development caller does not carry EXPOSE_SOURCE_NAMES unless configured onto it")
    void stdioDevelopmentCallerDoesNotExposeSourceNamesByDefault() {
        // stdio has no per-request context extractor, so a call always falls
        // through to the transport's configured development caller — exactly
        // like an empty transport context on any other transport.
        AliasAwareOrchestrator orchestrator = new AliasAwareOrchestrator();
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                PrivacyMetrics.none(), audit(), FIXED,
                developmentCaller(Set.of()), ToolOptions.defaults().noAdmission().build());

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                emptyExchange(), new McpSchema.CallToolRequest(
                        GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(orchestrator.investigationContexts).singleElement()
                .satisfies(ic -> assertThat(ic.has(Capability.EXPOSE_SOURCE_NAMES)).isFalse());
        assertThat(text(result)).contains("ALIASED-SOURCE").doesNotContain("customer-api");
    }

    @Test
    @DisplayName("real source names appear only once EXPOSE_SOURCE_NAMES is explicitly configured onto the principal")
    void realSourceNamesRequireExplicitCapability() {
        AliasAwareOrchestrator orchestrator = new AliasAwareOrchestrator();
        GetEntityContextTool tool = new GetEntityContextTool(orchestrator,
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT", Capability.EXPOSE_SOURCE_NAMES)),
                scopeResolver(), PrivacyMetrics.none(), audit(), FIXED,
                developmentCaller(Set.of()), ToolOptions.defaults().noAdmission().build());

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                emptyExchange(), new McpSchema.CallToolRequest(
                        GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(orchestrator.investigationContexts).singleElement()
                .satisfies(ic -> assertThat(ic.has(Capability.EXPOSE_SOURCE_NAMES)).isTrue());
        assertThat(text(result)).contains("customer-api").doesNotContain("ALIASED-SOURCE");
    }

    @Test
    @DisplayName("streamableHttp confines the SDK's context extractor type to the builder call")
    void streamableHttpBuildsFromASuppliedExtractor() {
        DataPrismMcpServer.HttpTransport transport = DataPrismMcpServer.streamableHttp(
                new NeverCalledOrchestrator(), authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")),
                scopeResolver(), request -> McpTransportContext.EMPTY, "/mcp",
                PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission().build());

        assertThat(transport.server()).isNotNull();
        assertThat(transport.transportProvider()).isNotNull();
        assertThat(transport.server().listTools()).extracting(McpSchema.Tool::name)
                .containsExactly(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME);
    }

    @Test
    @DisplayName("both factories report the build's project.version as serverInfo, not a hardcoded literal")
    void serverInfoReportsTheBuildVersion() {
        String expected = System.getProperty("dataprism.expected-version");
        assertThat(expected).isNotBlank();
        McpSyncServer stdio = DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), true, false, PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission().build());
        try {
            assertThat(stdio.getServerInfo().version()).isEqualTo(expected);
        } finally {
            stdio.closeGracefully();
        }
        DataPrismMcpServer.HttpTransport http = DataPrismMcpServer.streamableHttp(
                new NeverCalledOrchestrator(), authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")),
                scopeResolver(), request -> McpTransportContext.EMPTY, "/mcp",
                PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission().build());
        assertThat(http.server().getServerInfo().version()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the admission overloads still list exactly the two tools")
    void admissionOverloadsListExactlyTwoTools() {
        io.github.aindriub.dataprism.orchestration.ParameterFingerprinter fingerprinter =
                new io.github.aindriub.dataprism.orchestration.ParameterFingerprinter(
                        io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider.of(
                                "task-101-test-key-not-for-any-real-data-32b"));
        McpSyncServer stdio = DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), true, false, PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().admission(io.github.aindriub.dataprism.security.ToolAdmission.none(), fingerprinter).build());
        try {
            assertThat(stdio.listTools()).extracting(McpSchema.Tool::name)
                    .containsExactly(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME);
        } finally {
            stdio.closeGracefully();
        }
        DataPrismMcpServer.HttpTransport http = DataPrismMcpServer.streamableHttp(
                new NeverCalledOrchestrator(), authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")),
                scopeResolver(), request -> McpTransportContext.EMPTY, "/mcp",
                PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().admission(io.github.aindriub.dataprism.security.ToolAdmission.none(), fingerprinter).build());
        assertThat(http.server().listTools()).extracting(McpSchema.Tool::name)
                .containsExactly(GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME);
    }

    private static McpSyncServerExchange emptyExchange() {
        return new McpSyncServerExchange(
                new McpAsyncServerExchange("session-1", null, null, null, McpTransportContext.EMPTY));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /** Proves a refusal never got as far as touching the pipeline. */
    private static final class NeverCalledOrchestrator implements ContextOrchestrator {
        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            throw new AssertionError("orchestrator must not be reached");
        }
    }

    /** Names the source the way {@code SourceAliasing} would: real only under the capability. */
    private static final class AliasAwareOrchestrator implements ContextOrchestrator {
        final List<InvestigationContext> investigationContexts = new ArrayList<>();

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            investigationContexts.add(investigationContext);
            String sourceName = investigationContext.has(Capability.EXPOSE_SOURCE_NAMES)
                    ? "customer-api" : "ALIASED-SOURCE";
            return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(sourceName, "ANSWERED"),
                    List.of(), DataPrismObjectMapper.create().createObjectNode());
        }
    }
}
