package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.mcp.ToolOptions;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueries;
import io.github.aindriub.dataprism.orchestration.catalog.PagingSettings;
import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-invalid input over a real HTTP transport: {@link DataPrismMcpServer#streamableHttp} on an embedded
 * servlet container, driven by the SDK's own MCP client, with the catalog passed directly (no Spring wiring).
 * No handler is called directly, so the SDK's {@code tools/call} wrapper runs in front of every one.
 */
class ToolInputHttpTest {

    private static final String SENTINEL = "SENTINEL-d3adb33f";
    private static final String CALLER_HEADER = "X-Test-Role";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Set<String> ALL = Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES", "LIST_SOURCES",
            "DESCRIBE_SOURCE");
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-300-test-key-not-for-any-real-data-32b");

    @TempDir
    Path tempDir;

    private Tomcat tomcat;
    private final List<AuditEvent> audited = new CopyOnWriteArrayList<>();
    private final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);

    @AfterEach
    void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    record Thing(String id) {
    }

    private static final class MapKeys implements SecretKeyProvider {
        @Override
        public byte[] secret(String keyId) {
            return switch (keyId) {
                case "pseudo-key" -> "pseudonymisation-key-0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
                case "link-key" -> "link-key-material-0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);
                default -> throw new IllegalStateException("unknown key");
            };
        }
    }

    /** Starts the server. A request with no {@value #CALLER_HEADER} header has no authenticated caller. */
    private int start() throws Exception {
        // See McpHttpEndToEndTest for why this is forced before Tomcat's webapp classloader exists.
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });
        PrivacyMetrics metrics = new PrivacyMetrics() {
            @Override
            public synchronized void increment(Metric metric) {
                counts.merge(metric, 1, Integer::sum);
            }

            @Override
            public void increment(Metric metric, String sourceName) {
                increment(metric);
            }

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {
            }
        };
        List<DataSourceAdapter<?>> adapters = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String name = String.format("src-%03d", i);
            adapters.add(new DataSourceAdapter<Thing>() {
                @Override
                public String sourceName() {
                    return name;
                }

                @Override
                public Class<Thing> responseType() {
                    return Thing.class;
                }

                @Override
                public Thing fetch(DataRequest request) {
                    throw new AssertionError("an invalid call never reaches a source");
                }
            });
        }
        LinkKeys keys = new LinkKeys(new MapKeys(), "link-key", "pseudo-key");
        LinkCodec codec = new LinkCodec(
                new LinkSettings("link-key", Optional.empty(), 1024, true, Duration.ofSeconds(30)), keys, FIXED);
        ContextOrchestrator orchestrator = new ContextOrchestrator() {
            @Override
            public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                                InvestigationContext caller) {
                throw new AssertionError("an invalid call never reaches the orchestrator");
            }

            @Override
            public Optional<ModelDescription> describe(String sourceName, PrivacyContext privacy, boolean links) {
                return Optional.of(new ModelDescription(List.of(
                        new FieldProtection("id", FieldProtection.Protection.ABSENT, null, "thing-ref")),
                        ModelDescription.Undeclared.REFUSE));
            }
        };
        CatalogQueries catalog = new CatalogQueries(SourceCatalog.build(adapters, Map.of()),
                SourceVisibility.allowAll(), new SourceAliasing(new HmacValueTokenSource(KEYS)),
                new RealNamesPolicy(List.of("*")), orchestrator, List.of(new SensitivePatternValidator()),
                new PagingSettings(2, 100, Duration.ofMinutes(15)),
                new CatalogQueries.PageLinks(codec, keys, new LinkAttemptGate(codec,
                        new InMemoryLinkAttemptLimiter(5, Duration.ofMinutes(10), FIXED))),
                true, metrics, FIXED);
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", ALL, "limited", Set.of("GET_ENTITY_CONTEXT")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION,
                ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");

        DataPrismMcpServer.HttpTransport transport = DataPrismMcpServer.streamableHttp(orchestrator, authz, scopes,
                request -> {
                    String role = request.getHeader(CALLER_HEADER);
                    return role == null ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY,
                            new AuthenticatedCaller("principal-1", "client-1", Set.of(role), "demonstration",
                                    "case-1", null)));
                },
                "/mcp", metrics, audit, FIXED, ToolOptions.defaults().noAdmission().build(), catalog, 1024);

        tomcat = new Tomcat();
        tomcat.setBaseDir(tempDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", tempDir.toString());
        Tomcat.addServlet(context, "mcp", transport.transportProvider()).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    private static McpSyncClient client(int port, String role) throws Exception {
        // An explicit SSLContext: see AuditSinkFailureAbortsResponseTest for why the JDK default is not touched.
        javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init((java.security.KeyStore) null);
        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (role != null) {
            request.header(CALLER_HEADER, role);
        }
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport
                        .builder("http://localhost:" + port).endpoint("/mcp")
                        .clientBuilder(HttpClient.newBuilder().sslContext(ssl)).requestBuilder(request).build())
                .clientInfo(new McpSchema.Implementation("tool-input-http-test", "1.0.0")).build();
        client.initialize();
        return client;
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static String correlation(McpSchema.CallToolResult result) {
        return (String) result.meta().get("io.github.aindriub.dataprism/correlationId");
    }

    /** The result with its correlation id removed: what two refusals are compared on. */
    private static String withoutCorrelation(McpSchema.CallToolResult result) {
        return result.isError() + "|" + text(result) + "|" + result.structuredContent();
    }

    private int denied() {
        return counts.getOrDefault(Metric.MCP_DENIED, 0);
    }

    @Test
    @DisplayName("a schema-invalid call to each tool is TOOL_INPUT_INVALID with one DENY event, counted as denied")
    void invalidCallIsRefusedAuditedAndCounted() throws Exception {
        int port = start();
        Map<String, Map<String, Object>> bad = new LinkedHashMap<>();
        bad.put("get_entity_context", args("entityType", 5, "subjectId", "x"));
        bad.put("compare_entity_sources", args("entityType", "CUSTOMER"));
        bad.put("list_sources", args("pageSize", 0));
        bad.put("describe_source", args("source", 5));
        Map<String, String> entityType = Map.of("get_entity_context", "<unregistered>",
                "compare_entity_sources", "<unregistered>", "list_sources", "<unregistered>", "describe_source", "");

        McpSyncClient client = client(port, "investigator");
        try {
            for (Map.Entry<String, Map<String, Object>> entry : bad.entrySet()) {
                int events = audited.size();
                int before = denied();

                McpSchema.CallToolResult result = call(client, entry.getKey(), entry.getValue());

                assertThat(result.isError()).as(entry.getKey()).isTrue();
                assertThat(text(result)).as(entry.getKey()).isEqualTo("TOOL_INPUT_INVALID");
                assertThat(audited).as(entry.getKey()).hasSize(events + 1);
                AuditEvent event = audited.get(events);
                assertThat(event.tool()).isEqualTo(entry.getKey());
                assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_INPUT_INVALID");
                assertThat(event.entityType()).isEqualTo(entityType.get(entry.getKey()));
                assertThat(event.correlationId()).isEqualTo(correlation(result));
                assertThat(denied()).as(entry.getKey()).isEqualTo(before + 1);
            }
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("a call with a page key, however invalid, is a forged link: LINK_INVALID, counted once each")
    void invalidPageIsAForgedLink() throws Exception {
        int port = start();
        List<Object> invalidPages = new ArrayList<>();
        invalidPages.add("a".repeat(1025));
        invalidPages.add("");
        invalidPages.add(7);
        invalidPages.add(null);

        McpSyncClient client = client(port, "investigator");
        try {
            // Not the limiter's first count: it is a forged link of a valid shape, the baseline to compare.
            McpSchema.CallToolResult forged = call(client, "list_sources", args("page", "AAAAAAAAAAAAAAAAAAAAAAAA"));
            assertThat(text(forged)).isEqualTo("The link is not valid.");
            audited.clear();
            for (Object page : invalidPages) {
                int events = audited.size();
                int before = denied();

                McpSchema.CallToolResult result = call(client, "list_sources", args("page", page));

                assertThat(withoutCorrelation(result)).isEqualTo(withoutCorrelation(forged));
                assertThat(audited).hasSize(events + 1);
                assertThat(audited.get(events).policyDecision()).isEqualTo("DENY:LINK_INVALID");
                assertThat(audited.get(events).fieldDispositions()).containsEntry("link", "REFUSED:LINK_MALFORMED");
                assertThat(denied()).isEqualTo(before + 1);
            }
            // One forged link and four invalid pages are five counted failures, the limit; the next is over it.
            McpSchema.CallToolResult exhausted = call(client, "list_sources", args("page", 7));
            assertThat(text(exhausted)).isEqualTo("LINK_ATTEMPTS_EXHAUSTED");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("no authenticated caller: the same event shape for valid and invalid input, and no limiter count")
    void unauthenticatedValidAndInvalidAreAlike() throws Exception {
        int port = start();
        McpSyncClient anonymous = client(port, null);
        McpSyncClient known = client(port, "investigator");
        try {
            List<McpSchema.CallToolResult> results = List.of(
                    call(anonymous, "get_entity_context", args("entityType", 5, "subjectId", "x")),
                    call(anonymous, "get_entity_context", args("entityType", "CUSTOMER", "subjectId", "x")),
                    call(anonymous, "list_sources", args("page", 7)),
                    call(anonymous, "list_sources", args()));

            for (McpSchema.CallToolResult result : results) {
                assertThat(text(result)).isEqualTo("NO_AUTHENTICATED_CALLER");
            }
            assertThat(audited).hasSize(4);
            for (AuditEvent event : audited) {
                assertThat(event.principalId()).isEqualTo("unauthenticated");
                assertThat(event.policyDecision()).isEqualTo("DENY:NO_AUTHENTICATED_CALLER");
                assertThat(event.fieldDispositions()).doesNotContainKey("link");
            }
            assertThat(audited.get(0).entityType()).isEqualTo("<unregistered>");
            assertThat(audited.get(1).entityType()).isEqualTo("CUSTOMER");
            assertThat(denied()).isEqualTo(4);
            // The anonymous attempts counted nothing against the known principal: five more forged links fit.
            for (int i = 0; i < 5; i++) {
                assertThat(text(call(known, "list_sources", args("page", 7)))).isEqualTo("The link is not valid.");
            }
        } finally {
            anonymous.closeGracefully();
            known.closeGracefully();
        }
    }

    @Test
    @DisplayName("a role without the capability gets TOOL_NOT_PERMITTED for valid and invalid input alike")
    void notPermittedWinsOverInvalidInput() throws Exception {
        int port = start();
        McpSyncClient client = client(port, "limited");
        try {
            for (Map<String, Object> arguments : List.of(args(), args("pageSize", 0), args("page", 7))) {
                assertThat(text(call(client, "list_sources", arguments))).isEqualTo("TOOL_NOT_PERMITTED");
            }
            assertThat(text(call(client, "describe_source", args("source", "x")))).isEqualTo("TOOL_NOT_PERMITTED");
            assertThat(text(call(client, "describe_source", args("source", 5)))).isEqualTo("TOOL_NOT_PERMITTED");
        } finally {
            client.closeGracefully();
        }
        assertThat(audited).hasSize(5);
        assertThat(audited).allSatisfy(event -> assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_NOT_PERMITTED"));
    }

    @Test
    @DisplayName("a client text sentinel in every argument reaches no result, audit record or log line")
    void noClientTextEscapes() throws Exception {
        PrintStream originalErr = System.err;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream redirect = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setErr(redirect);
        System.setOut(redirect);
        List<String> results = new ArrayList<>();
        Map<String, Object> object = Map.of("x", SENTINEL);
        try {
            int port = start();
            McpSyncClient client = client(port, "investigator");
            try {
                for (String tool : List.of("get_entity_context", "compare_entity_sources")) {
                    results.add(call(client, tool, args("entityType", SENTINEL, "subjectId", object,
                            "targetSources", List.of(SENTINEL, SENTINEL), "principalId", SENTINEL,
                            "approvalId", SENTINEL)).toString());
                }
                results.add(call(client, "list_sources", args("entityType", SENTINEL + " bad",
                        "keyNamespace", SENTINEL, "pageSize", SENTINEL, "page", SENTINEL + "!",
                        "scopeId", SENTINEL)).toString());
                results.add(call(client, "describe_source", args("source", object, "extra", SENTINEL,
                        "caseId", SENTINEL)).toString());
            } finally {
                client.closeGracefully();
            }
        } finally {
            System.setErr(originalErr);
            System.setOut(originalOut);
        }

        assertThat(audited).hasSize(4);
        assertThat(String.join("\n", results)).doesNotContain(SENTINEL);
        assertThat(audited.toString()).doesNotContain(SENTINEL);
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain(SENTINEL)
                .doesNotContain("input validation failed");
    }

    @Test
    @DisplayName("two different violations give identical TOOL_INPUT_INVALID results apart from the correlation id")
    void violationsAreNotDistinguishable() throws Exception {
        int port = start();
        McpSyncClient client = client(port, "investigator");
        try {
            McpSchema.CallToolResult wrongType = call(client, "get_entity_context",
                    args("entityType", 5, "subjectId", "x"));
            McpSchema.CallToolResult blank = call(client, "get_entity_context",
                    args("entityType", " ", "subjectId", "x"));
            McpSchema.CallToolResult emptyList = call(client, "get_entity_context",
                    args("entityType", "CUSTOMER", "subjectId", "x", "targetSources", List.of()));

            assertThat(text(wrongType)).isEqualTo("TOOL_INPUT_INVALID");
            assertThat(withoutCorrelation(blank)).isEqualTo(withoutCorrelation(wrongType));
            assertThat(withoutCorrelation(emptyList)).isEqualTo(withoutCorrelation(wrongType));
            assertThat(correlation(blank)).isNotEqualTo(correlation(wrongType));
        } finally {
            client.closeGracefully();
        }
    }
}
