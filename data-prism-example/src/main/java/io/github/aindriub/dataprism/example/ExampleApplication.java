package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.server.McpSyncServer;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Runs the MCP server on stdio.
 *
 * <p>Under stdio transport, standard output <em>is</em> the protocol: one stray
 * {@code System.out.println}, banner or console appender corrupts the JSON-RPC
 * stream, and the failure looks like a client parse error rather than a logging
 * mistake. Everything diagnostic goes to stderr, which is what
 * {@code simplelogger.properties} pins.
 *
 * <p>stdio cannot carry a per-request identity, so every call on this transport
 * is attributed to one development caller — explicitly acknowledged as such via
 * {@link DataPrismMcpServer#stdio}'s {@code singlePrincipalDevelopmentMode}
 * argument, never a default a caller could reach by omission. That caller holds
 * {@code GET_ENTITY_CONTEXT} and nothing more: no {@code EXPOSE_SOURCE_NAMES},
 * so this example shows the same scope-local source aliases a real deployment
 * would show a caller not granted that capability.
 */
public final class ExampleApplication {

    private static final String DEVELOPMENT_PURPOSE = "demonstration";
    private static final String DEVELOPMENT_CASE = "CASE-DEMO-1";
    private static final String DEVELOPMENT_ROLE = "developer";

    private ExampleApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        DataPrismAssembly assembly = DataPrismAssembly.standard();

        SecurityPolicy policy = new SecurityPolicy(Set.of(DEVELOPMENT_PURPOSE),
                Map.of(DEVELOPMENT_ROLE, Set.of(Capability.GET_ENTITY_CONTEXT)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(DEVELOPMENT_PURPOSE)));
        AuthenticatedCaller developmentCaller = new AuthenticatedCaller("stdio-development", "stdio-development",
                Set.of(DEVELOPMENT_ROLE), DEVELOPMENT_PURPOSE, DEVELOPMENT_CASE, null);
        AuditRecorder toolAudit = new AuditRecorder(new Slf4jAuditSink(), assembly.clock(), "example-1-mcp");

        McpSyncServer server = DataPrismMcpServer.stdio(assembly.orchestrator(), authorizationService,
                scopeResolver, developmentCaller, true, false, PrivacyMetrics.none(), toolAudit, assembly.clock());

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        System.err.println("data-prism listening on stdio; tool: get_entity_context");

        Thread.currentThread().join();
    }
}
