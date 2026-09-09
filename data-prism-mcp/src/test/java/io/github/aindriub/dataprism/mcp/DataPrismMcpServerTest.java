package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
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
                developmentCaller(Set.of()), false, false, PrivacyMetrics.none(), audit(), FIXED))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo("STDIO_DEVELOPMENT_ONLY"));
    }

    @Test
    @DisplayName("stdio refuses to start when the deployment says it is production, even in development mode")
    void stdioRefusesWhenProductionIsSet() {
        assertThatThrownBy(() -> DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), true, true, PrivacyMetrics.none(), audit(), FIXED))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo("STDIO_DEVELOPMENT_ONLY"));
    }

    @Test
    @DisplayName("stdio starts when singlePrincipalDevelopmentMode is explicitly true and production is not set")
    void stdioStartsWithExplicitDevelopmentMode() {
        McpSyncServer server = DataPrismMcpServer.stdio(new NeverCalledOrchestrator(),
                authorizationServiceGranting(Set.of("GET_ENTITY_CONTEXT")), scopeResolver(),
                developmentCaller(Set.of()), true, false, PrivacyMetrics.none(), audit(), FIXED);
        try {
            assertThat(server.listTools()).extracting(McpSchema.Tool::name)
                    .containsExactly(GetEntityContextTool.NAME);
        } finally {
            server.closeGracefully();
        }
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
                DataPrismObjectMapper.create(), PrivacyMetrics.none(), audit(), FIXED,
                developmentCaller(Set.of()));

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
                scopeResolver(), DataPrismObjectMapper.create(), PrivacyMetrics.none(), audit(), FIXED,
                developmentCaller(Set.of()));

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
                scopeResolver(), request -> McpTransportContext.EMPTY, PrivacyMetrics.none(), audit(), FIXED);

        assertThat(transport.server()).isNotNull();
        assertThat(transport.transportProvider()).isNotNull();
        assertThat(transport.server().listTools()).extracting(McpSchema.Tool::name)
                .containsExactly(GetEntityContextTool.NAME);
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
