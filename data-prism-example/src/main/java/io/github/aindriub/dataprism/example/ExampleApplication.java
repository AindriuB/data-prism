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

    /**
     * The one profile name that refuses stdio. Matched case-insensitively
     * against a comma-separated list, the same shape Spring itself accepts for
     * {@code spring.profiles.active} — this launcher reads that convention
     * directly rather than starting a Spring context to ask it, which would put
     * a banner on the stream stdio's own protocol occupies.
     */
    private static final String PRODUCTION_PROFILE = "production";

    private ExampleApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        McpSyncServer server = buildServer(activeProfiles());

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        System.err.println("data-prism listening on stdio; tool: get_entity_context");

        Thread.currentThread().join();
    }

    /**
     * Everything {@link #main} does except register the shutdown hook and block
     * on {@code join()} — visible for {@code StdioProductionProfileTest}, which
     * wants the server (or the refusal) without a launcher that never returns.
     *
     * @throws io.github.aindriub.dataprism.security.SecurityRefusedException with code
     *                                  {@code STDIO_DEVELOPMENT_ONLY} when {@code activeProfiles}
     *                                  names the production profile
     */
    static McpSyncServer buildServer(String activeProfiles) {
        DataPrismAssembly assembly = DataPrismAssembly.standard();

        SecurityPolicy policy = shippedSecurityPolicy();
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(DEVELOPMENT_PURPOSE)));
        AuthenticatedCaller developmentCaller = new AuthenticatedCaller("stdio-development", "stdio-development",
                Set.of(DEVELOPMENT_ROLE), DEVELOPMENT_PURPOSE, DEVELOPMENT_CASE, null);
        AuditRecorder toolAudit = new AuditRecorder(new Slf4jAuditSink(), assembly.clock(), "example-1-mcp");

        return DataPrismMcpServer.stdio(assembly.orchestrator(), authorizationService,
                scopeResolver, developmentCaller, true, isProductionProfile(activeProfiles),
                PrivacyMetrics.none(), toolAudit, assembly.clock());
    }

    /**
     * The shipped stdio development policy: one role, {@code developer}, holding
     * only {@link Capability#GET_ENTITY_CONTEXT}. A named factory rather than a
     * value built inline in {@link #buildServer}, so {@code ShippedDefaultsTest}
     * can assert on the same policy {@code main} actually runs, instead of a
     * lookalike the test builds itself.
     */
    static SecurityPolicy shippedSecurityPolicy() {
        return new SecurityPolicy(Set.of(DEVELOPMENT_PURPOSE),
                Map.of(DEVELOPMENT_ROLE, Set.of(Capability.GET_ENTITY_CONTEXT)));
    }

    /**
     * {@code spring.profiles.active} read the way the JVM itself would set it —
     * a system property, falling back to the environment variable Spring
     * documents for the same purpose — without touching a Spring API, since
     * nothing here is a Spring application.
     */
    private static String activeProfiles() {
        String systemProperty = System.getProperty("spring.profiles.active");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }
        return System.getenv("SPRING_PROFILES_ACTIVE");
    }

    /** Visible for {@code StdioProductionProfileTest}. */
    static boolean isProductionProfile(String activeProfiles) {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }
        for (String profile : activeProfiles.split(",")) {
            if (profile.trim().equalsIgnoreCase(PRODUCTION_PROFILE)) {
                return true;
            }
        }
        return false;
    }
}
