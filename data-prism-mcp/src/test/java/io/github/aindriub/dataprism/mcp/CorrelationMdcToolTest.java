package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditRecorder;
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
import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.correlation.InboundCorrelation;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** The validated external id is on every log line of a tool call, on every thread of it, and only there. */
class CorrelationMdcToolTest {

    private static final String KEY = "transaction_id";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-148-test-key-not-for-any-real-data-32b");
    private static final CorrelationIdPolicy POLICY = CorrelationIdPolicy.opaque("[a-z0-9-]{1,40}");
    private static final String[] TOOLS = {GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME};
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final AuthenticatedCaller UNPRIVILEGED =
            new AuthenticatedCaller("principal-2", "client-2", Set.of("nobody"), "demonstration", "case-1", null);
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("mdc-test");

    private record Thing(String id) {
    }

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attach() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
    }

    /** A source that logs the call it is serving, and waits for the other call so the two overlap. */
    private static DataSourceAdapter<Thing> source(String name, CyclicBarrier overlap) {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return name; }
            @Override public Class<Thing> responseType() { return Thing.class; }
            @Override public Thing fetch(DataRequest request) {
                LOG.info("fetch {} for {}", name, request.subjectId());
                if (overlap != null) {
                    try {
                        overlap.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("calls did not overlap");
                    }
                }
                LOG.info("fetched {} for {}", name, request.subjectId());
                return new Thing("1");
            }
        };
    }

    private static ContextOrchestrator orchestrator(AuditRecorder audit, CorrelationMdc mdc, boolean overlap) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (src, ctx) ->
                new ScrubResult(new ObjectMapper().createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        List<DataSourceAdapter<?>> sources = new ArrayList<>();
        for (String name : List.of("src-a", "src-b", "src-c")) {
            sources.add(source(name, overlap ? new CyclicBarrier(2) : null));
        }
        return new DefaultContextOrchestrator(sources, scrubber, resolver,
                List.of(ok, new SensitivePatternValidator()), (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS), audit, new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults().withMdc(mdc)),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private static AuditRecorder loggingAudit() {
        return new AuditRecorder(event -> LOG.info("audit event"), FIXED, "test-mcp");
    }

    private static McpSchema.CallToolResult call(int tool, ContextOrchestrator orchestrator, AuditRecorder audit,
                                                 CorrelationMdc mdc, AuthenticatedCaller caller,
                                                 InboundCorrelation inbound, String subject) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopes = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
        ParameterFingerprinter fingerprinter = new ParameterFingerprinter(KEYS);
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler = tool == 0
                ? new GetEntityContextTool(orchestrator, authz, scopes, DataPrismObjectMapper.create(),
                        PrivacyMetrics.none(), audit, FIXED, null, ToolOptions.defaults().admission(ToolAdmission.none(), fingerprinter).correlationRequirement(CorrelationRequirement.OPTIONAL).mdc(mdc).build()).specification().callHandler()
                : new CompareEntitySourcesTool(orchestrator, authz, scopes, DataPrismObjectMapper.create(),
                        PrivacyMetrics.none(), audit, FIXED, null, ToolOptions.defaults().admission(ToolAdmission.none(), fingerprinter).correlationRequirement(CorrelationRequirement.OPTIONAL).mdc(mdc).build()).specification().callHandler();
        Map<String, Object> entries = new HashMap<>();
        entries.put(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller);
        entries.put(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY, inbound);
        McpSyncServerExchange exchange = new McpSyncServerExchange(
                new McpAsyncServerExchange("s", null, null, null, McpTransportContext.create(entries)));
        return handler.apply(exchange, new McpSchema.CallToolRequest(TOOLS[tool],
                Map.of("entityType", "THING", "subjectId", subject)));
    }

    private static InboundCorrelation present(String id) {
        return InboundCorrelation.present(POLICY.validate(id).orElseThrow());
    }

    @Test
    @DisplayName("with no key configured, a full call with an id present leaves every event's MDC map empty")
    void unsetMeansNothing() {
        for (int tool = 0; tool < 2; tool++) {
            appender.list.clear();
            AuditRecorder audit = loggingAudit();
            call(tool, orchestrator(audit, CorrelationMdc.off(), false), audit, CorrelationMdc.off(), CALLER,
                    present("synthetic-clid-0001"), "call-x");
            assertThat(appender.list.stream().filter(e -> e.getFormattedMessage().contains("call-x")))
                    .isNotEmpty();
            assertThat(appender.list).allSatisfy(e -> assertThat(e.getMDCPropertyMap()).isEmpty());
        }
    }

    @Test
    @DisplayName("a DENY path's audit line carries the key")
    void denyPathCarriesKey() {
        for (int tool = 0; tool < 2; tool++) {
            appender.list.clear();
            AuditRecorder audit = loggingAudit();
            McpSchema.CallToolResult result = call(tool, orchestrator(audit, CorrelationMdc.of(KEY), false), audit,
                    CorrelationMdc.of(KEY), UNPRIVILEGED, present("synthetic-clid-0001"), "call-x");
            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().equals("audit event"))
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry(KEY, "synthetic-clid-0001"));
        }
    }

    @Test
    @DisplayName("two concurrent calls each see only their own id, fan-out threads included")
    void concurrentCallsDoNotCross() throws Exception {
        for (int tool = 0; tool < 2; tool++) {
            appender.list.clear();
            CorrelationMdc mdc = CorrelationMdc.of(KEY);
            AuditRecorder audit = loggingAudit();
            ContextOrchestrator orchestrator = orchestrator(audit, mdc, true);
            final int t = tool;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> one = pool.submit(() -> call(t, orchestrator, audit, mdc, CALLER,
                        present("synthetic-clid-0001"), "call-one"));
                Future<?> two = pool.submit(() -> call(t, orchestrator, audit, mdc, CALLER,
                        present("synthetic-clid-0002"), "call-two"));
                one.get(20, TimeUnit.SECONDS);
                two.get(20, TimeUnit.SECONDS);
            }
            Map<String, String> expected = Map.of("call-one", "synthetic-clid-0001", "call-two", "synthetic-clid-0002");
            expected.forEach((marker, id) -> {
                List<ILoggingEvent> events = appender.list.stream()
                        .filter(e -> e.getFormattedMessage().contains(marker)).toList();
                assertThat(events).as(marker + " fan-out events").hasSizeGreaterThanOrEqualTo(3);
                assertThat(events).allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsOnly(Map.entry(KEY, id)));
            });
        }
    }

    @Test
    @DisplayName("a reused thread carries no key into the next task, and neither does the handler thread")
    void reuseLeavesNothing() throws Exception {
        CorrelationMdc mdc = CorrelationMdc.of(KEY);
        AuditRecorder audit = loggingAudit();
        try (ExecutorService single = Executors.newSingleThreadExecutor()) {
            single.submit(() -> call(0, orchestrator(audit, mdc, false), audit, mdc, CALLER,
                    present("synthetic-clid-0001"), "call-one")).get(20, TimeUnit.SECONDS);
            single.submit(() -> LOG.info("plain task")).get(20, TimeUnit.SECONDS);
        }
        assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().equals("plain task"))
                .singleElement().satisfies(e -> assertThat(e.getMDCPropertyMap()).isEmpty());
    }

    @Test
    @DisplayName("a rejected id proceeds and puts nothing in any event's MDC")
    void rejectedPutsNothing() {
        AuditRecorder audit = loggingAudit();
        McpSchema.CallToolResult result = call(0, orchestrator(audit, CorrelationMdc.of(KEY), false), audit,
                CorrelationMdc.of(KEY), CALLER, InboundCorrelation.rejected(), "call-x");
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(appender.list).isNotEmpty().allSatisfy(e -> {
            assertThat(e.getMDCPropertyMap()).doesNotContainKey(KEY);
            assertThat(e.getMDCPropertyMap().values()).noneMatch(v -> v.contains("rejected"));
        });
    }
}
