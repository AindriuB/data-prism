package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditChainVerifier;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditFieldMapping;
import io.github.aindriub.dataprism.audit.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditRouting;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.audit.FileAuditSink;
import io.github.aindriub.dataprism.audit.SegmentedJsonAuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.audit.TeeAuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.correlation.InboundCorrelation;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit record's {@code entityType} holds the argument only when it is acceptable (an exact configured
 * name, or an upper-case identifier when none is configured), on every DENY path of both tools and on the
 * orchestrator's own ALLOW and DENY records, in all three audit outputs. The caller still gets the same result.
 */
class UnregisteredEntityTypeAuditTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-150-test-key-not-for-any-real-data-32b");
    private static final AuditRouting ROUTING = new AuditRouting("dataprism.audit", "logs", "dataprism.audit", "prod");
    private static final String PLANTED = "ACC-1";
    private static final String[] TOOLS = {GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME};

    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final AuthenticatedCaller UNPRIVILEGED =
            new AuthenticatedCaller("principal-2", "client-2", Set.of("nobody"), "demonstration", "case-1", null);
    private static final AuthenticatedCaller BAD_PURPOSE =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "not-a-purpose", "case-1", null);

    private record Thing(String id, String value) {
    }

    private enum Path_ {
        NO_CALLER, CORRELATION_REQUIRED, CORRELATION_INVALID, AUTHORISATION_DENIED, SCOPE_REFUSED,
        ADMISSION_REFUSED, ALLOW, ORCHESTRATOR_DENY;

        boolean tool() {
            return ordinal() < ALLOW.ordinal();
        }
    }

    /** Everything one call produced: the client's result and what each audit output holds. */
    private record Outcome(McpSchema.CallToolResult result, List<String> nativeLines, List<String> ndjsonLines,
                           List<String> logMessages, Path nativeFile) {

        List<AuditEvent> events() {
            return nativeLines.stream().map(AuditRecordFormat::parse).toList();
        }

        String allOutputs() {
            return String.join("\n", nativeLines) + "\n" + String.join("\n", ndjsonLines) + "\n"
                    + String.join("\n", logMessages);
        }
    }

    private static DefaultContextOrchestrator orchestrator(AuditRecorder audit, Thing thing) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(new ObjectMapper().createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        DataSourceAdapter<Thing> source = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "thing-api";
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                return thing;
            }
        };
        return new DefaultContextOrchestrator(List.of(source), scrubber, resolver,
                List.of(ok, new SensitivePatternValidator()), (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS), audit, new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private static Outcome run(Path dir, int tool, Path_ path, AuditedEntityTypes types, String entityType)
            throws IOException {
        Path nativeFile = dir.resolve("audit.log");
        Path jsonDir = Files.createDirectory(dir.resolve("json"));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dataprism.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        McpSchema.CallToolResult result;
        try (FileAuditSink fileSink = new FileAuditSink(nativeFile);
             SegmentedJsonAuditSink jsonSink = new SegmentedJsonAuditSink(jsonDir, AuditFieldMapping.ecs(), ROUTING)) {
            AuditRecorder audit = new AuditRecorder(new TeeAuditSink(new TeeAuditSink(fileSink, jsonSink),
                    new Slf4jAuditSink(AuditFieldMapping.ecs(), ROUTING)), FIXED, "test-150");
            result = call(tool, path, types, entityType, audit);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        List<String> ndjson = new ArrayList<>();
        try (Stream<Path> listing = Files.list(jsonDir)) {
            for (Path file : listing.toList()) {
                ndjson.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        List<String> logs = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            StringBuilder line = new StringBuilder(event.getFormattedMessage());
            if (event.getKeyValuePairs() != null) {
                event.getKeyValuePairs().forEach(kv -> line.append(' ').append(kv.key).append('=').append(kv.value));
            }
            logs.add(line.toString());
        }
        return new Outcome(result, Files.readAllLines(nativeFile, StandardCharsets.UTF_8), ndjson, logs, nativeFile);
    }

    private static McpSchema.CallToolResult call(int tool, Path_ path, AuditedEntityTypes types, String entityType,
                                                 AuditRecorder audit) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        ParameterFingerprinter fingerprinter = new ParameterFingerprinter(KEYS);
        InMemoryOversightState state = new InMemoryOversightState();
        if (path == Path_.ADMISSION_REFUSED) {
            state.pauseAll();
        }
        ToolAdmission admission = new ToolAdmission(state, new InMemoryApprovalStore(),
                new InMemoryCallerRateLimiter(), OversightPolicy.none(), FIXED);
        CorrelationRequirement requirement = path == Path_.CORRELATION_REQUIRED || path == Path_.CORRELATION_INVALID
                ? CorrelationRequirement.REQUIRED : CorrelationRequirement.OPTIONAL;
        ContextOrchestrator orchestrator =
                orchestrator(audit, path == Path_.ORCHESTRATOR_DENY ? null : new Thing("1", "raw"));
        ObjectMapper mapper = DataPrismObjectMapper.create();
        var handler = tool == 0
                ? new GetEntityContextTool(orchestrator, authz, scopes, mapper, PrivacyMetrics.none(), audit, FIXED,
                        null, admission, fingerprinter, requirement, CorrelationMdc.off(), types)
                        .specification().callHandler()
                : new CompareEntitySourcesTool(orchestrator, authz, scopes, mapper, PrivacyMetrics.none(), audit,
                        FIXED, null, admission, fingerprinter, requirement, CorrelationMdc.off(), types)
                        .specification().callHandler();

        Map<String, Object> entries = new HashMap<>();
        AuthenticatedCaller caller = switch (path) {
            case NO_CALLER -> null;
            case AUTHORISATION_DENIED -> UNPRIVILEGED;
            case SCOPE_REFUSED -> BAD_PURPOSE;
            default -> CALLER;
        };
        if (caller != null) {
            entries.put(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller);
        }
        if (path == Path_.CORRELATION_INVALID) {
            entries.put(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY, InboundCorrelation.rejected());
        } else if (path == Path_.CORRELATION_REQUIRED) {
            entries.put(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY, InboundCorrelation.absent());
        }
        McpSyncServerExchange exchange = new McpSyncServerExchange(
                new McpAsyncServerExchange("s", null, null, null, McpTransportContext.create(entries)));
        return handler.apply(exchange, new McpSchema.CallToolRequest(TOOLS[tool],
                Map.of("entityType", entityType, "subjectId", "x")));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static Path fresh(Path root) throws IOException {
        return Files.createTempDirectory(root, "case");
    }

    private static void assertAudited(Outcome out, String expectedEntityType, String plantedAbsent) {
        assertThat(out.nativeLines).as("native lines").hasSize(1);
        assertThat(out.events()).singleElement().satisfies(e -> assertThat(e.entityType()).isEqualTo(expectedEntityType));
        assertThat(out.ndjsonLines).as("ndjson lines").hasSize(1);
        assertThat(out.logMessages).as("log events").hasSize(1);
        assertThat(out.logMessages.get(0)).contains("entityType=" + expectedEntityType + " ");
        if (plantedAbsent != null) {
            assertThat(out.allOutputs()).as("all three audit outputs").doesNotContain(plantedAbsent);
        }
    }

    @TestFactory
    Stream<DynamicTest> plantedEntityTypeIsAuditedAsTheSentinel(@TempDir Path root) {
        List<DynamicTest> tests = new ArrayList<>();
        for (int tool = 0; tool < 2; tool++) {
            for (Path_ path : Path_.values()) {
                int toolIndex = tool;
                tests.add(DynamicTest.dynamicTest(TOOLS[tool] + " " + path, () -> {
                    Outcome out = run(fresh(root), toolIndex, path, AuditedEntityTypes.shape(), PLANTED);

                    assertAudited(out, AuditedEntityTypes.UNREGISTERED, PLANTED);
                    String decision = out.events().get(0).policyDecision();
                    if (path == Path_.ALLOW) {
                        assertThat(decision).isEqualTo("ALLOW");
                        assertThat(out.result().isError()).isNotEqualTo(Boolean.TRUE);
                        // The caller still sees the value it sent.
                        assertThat(out.result().structuredContent().toString()).contains(PLANTED);
                    } else {
                        assertThat(out.result().isError()).isEqualTo(Boolean.TRUE);
                        assertThat(decision).startsWith("DENY:");
                        if (path.tool()) {
                            assertThat(text(out.result())).isEqualTo(decision.substring("DENY:".length()));
                        } else {
                            assertThat(decision).isEqualTo("DENY:NO_SOURCE_DATA");
                        }
                    }
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> refusalCodesAreTheDocumentedOnes(@TempDir Path root) {
        Map<Path_, String> codes = Map.of(
                Path_.NO_CALLER, GetEntityContextTool.NO_AUTHENTICATED_CALLER,
                Path_.CORRELATION_REQUIRED, DataPrismMcpServer.EXTERNAL_CORRELATION_ID_REQUIRED,
                Path_.CORRELATION_INVALID, DataPrismMcpServer.EXTERNAL_CORRELATION_ID_INVALID);
        List<DynamicTest> tests = new ArrayList<>();
        for (int tool = 0; tool < 2; tool++) {
            for (Map.Entry<Path_, String> code : codes.entrySet()) {
                int toolIndex = tool;
                tests.add(DynamicTest.dynamicTest(TOOLS[tool] + " " + code.getKey(), () -> {
                    Outcome out = run(fresh(root), toolIndex, code.getKey(), AuditedEntityTypes.shape(), PLANTED);
                    assertThat(out.result().isError()).isEqualTo(Boolean.TRUE);
                    assertThat(text(out.result())).isEqualTo(code.getValue());
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> aRegisteredTypeIsAuditedVerbatim(@TempDir Path root) {
        AuditedEntityTypes registered = AuditedEntityTypes.of(List.of("CUSTOMER"));
        List<DynamicTest> tests = new ArrayList<>();
        for (int tool = 0; tool < 2; tool++) {
            for (Path_ path : List.of(Path_.NO_CALLER, Path_.AUTHORISATION_DENIED, Path_.ALLOW,
                    Path_.ORCHESTRATOR_DENY)) {
                int toolIndex = tool;
                tests.add(DynamicTest.dynamicTest(TOOLS[tool] + " " + path, () -> {
                    Outcome out = run(fresh(root), toolIndex, path, registered, "CUSTOMER");

                    assertAudited(out, "CUSTOMER", null);
                    assertThat(out.ndjsonLines.get(0)).contains("CUSTOMER");
                    var report = AuditChainVerifier.verify(out.nativeFile());
                    // The conditions under which AuditChainVerifierCli exits with EXIT_INTACT.
                    assertThat(report.hasBreak()).isFalse();
                    assertThat(report.hasCheckpointFinding()).isFalse();
                    assertThat(report.hasStructuralAnomaly()).isFalse();
                    assertThat(report.anomalies()).isEmpty();
                    assertThat(report.tail()).isEmpty();
                    assertThat(report.writers()).isNotEmpty();
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> aShapedTypeIsAuditedVerbatimWhenNothingIsConfigured(@TempDir Path root) {
        List<DynamicTest> tests = new ArrayList<>();
        for (int tool = 0; tool < 2; tool++) {
            for (Path_ path : List.of(Path_.NO_CALLER, Path_.ALLOW)) {
                int toolIndex = tool;
                tests.add(DynamicTest.dynamicTest(TOOLS[tool] + " " + path, () -> {
                    Outcome out = run(fresh(root), toolIndex, path, AuditedEntityTypes.shape(), "CUSTOMER");
                    assertAudited(out, "CUSTOMER", null);
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> withAListSetTheShapeFallbackIsOff(@TempDir Path root) {
        AuditedEntityTypes registered = AuditedEntityTypes.of(List.of("CUSTOMER"));
        List<DynamicTest> tests = new ArrayList<>();
        for (int tool = 0; tool < 2; tool++) {
            for (Path_ path : List.of(Path_.NO_CALLER, Path_.ALLOW)) {
                int toolIndex = tool;
                tests.add(DynamicTest.dynamicTest(TOOLS[tool] + " " + path, () -> {
                    // ORDER passes the shape but is not on the list.
                    Outcome out = run(fresh(root), toolIndex, path, registered, "ORDER");

                    assertAudited(out, AuditedEntityTypes.UNREGISTERED, "ORDER");
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void theGateIsExercisedByAnUnconfiguredRegistryToo(@TempDir Path root) throws IOException {
        Outcome out = run(fresh(root), 0, Path_.ALLOW, AuditedEntityTypes.of(List.of()), PLANTED);

        assertAudited(out, AuditedEntityTypes.UNREGISTERED, PLANTED);
    }

    /**
     * The stdio factory overload that takes a registry must hand it to both tools. It is driven over a real
     * stdio transport, the only way to call a server this factory builds.
     */
    @Test
    void theStdioFactoryOverloadDeliversTheRegistryToTheTools() throws Exception {
        java.util.concurrent.CopyOnWriteArrayList<AuditEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        AuditRecorder audit = new AuditRecorder(e -> {
            events.add(e);
            both.countDown();
        }, FIXED, "test-150-stdio");
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"synthetic-client\",\"version\":\"1\"}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
        java.io.PipedOutputStream feed = new java.io.PipedOutputStream();
        java.io.InputStream originalIn = System.in;
        java.io.PrintStream originalOut = System.out;
        io.modelcontextprotocol.server.McpSyncServer server = null;
        try {
            System.setIn(new java.io.PipedInputStream(feed));
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            server = DataPrismMcpServer.stdio(orchestrator(audit, new Thing("1", "raw")), authz, scopes, CALLER,
                    true, false, PrivacyMetrics.none(), audit, FIXED, CorrelationRequirement.OPTIONAL,
                    AuditedEntityTypes.of(List.of("CUSTOMER")));
            feed.write(init.getBytes(StandardCharsets.UTF_8));
            for (String tool : TOOLS) {
                feed.write(("{\"jsonrpc\":\"2.0\",\"id\":\"" + tool + "\",\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"" + tool + "\",\"arguments\":{\"entityType\":\"ORDER\",\"subjectId\":\"x\"}}}\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            feed.flush();
            assertThat(both.await(20, java.util.concurrent.TimeUnit.SECONDS)).as("both calls audited").isTrue();
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            if (server != null) {
                server.closeGracefully();
            }
            feed.close();
        }

        // ORDER passes the shape but is not on the list, so the registry (not the shape fallback) decided.
        assertThat(events).hasSize(2).allSatisfy(e -> {
            assertThat(e.policyDecision()).isEqualTo("ALLOW");
            assertThat(e.entityType()).isEqualTo(AuditedEntityTypes.UNREGISTERED);
        });
    }
}
