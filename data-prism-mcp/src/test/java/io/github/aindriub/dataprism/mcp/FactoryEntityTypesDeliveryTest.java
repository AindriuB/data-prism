package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
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
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 150 regression, kept for task 155's single factories: each factory must hand the caller's
 * {@link AuditedEntityTypes} to both tools. The registry names a mixed-case type that the shape fallback
 * would audit as the sentinel, so a factory that drops the registry audits {@code <unregistered>} and fails
 * here. Each tool is called on a DENY path (the caller holds no capability), over the factory's own transport.
 */
class FactoryEntityTypesDeliveryTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String[] TOOLS = {GetEntityContextTool.NAME, CompareEntitySourcesTool.NAME};
    /** Registry-valid, shape-invalid: only a delivered registry makes this survive into the audit record. */
    private static final String REGISTERED = "Customer-1";
    private static final AuthenticatedCaller UNPRIVILEGED =
            new AuthenticatedCaller("principal-2", "client-2", Set.of("nobody"), "demonstration", "case-1", null);

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"synthetic-client\",\"version\":\"1\"}}}";
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch both = new CountDownLatch(2);

    private AuditRecorder audit() {
        return new AuditRecorder(e -> {
            events.add(e);
            both.countDown();
        }, FIXED, "test-155");
    }

    private static AuthorizationService authz() {
        return new AuthorizationService(new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES"))),
                "DEFAULT", PrivacyScopeType.INVESTIGATION);
    }

    private static ScopeResolver scopes() {
        return new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
    }

    private static String call(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + tool + "\",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"" + tool + "\",\"arguments\":{\"entityType\":\"" + REGISTERED
                + "\",\"subjectId\":\"x\"}}}";
    }

    private void assertDenyAuditedAsRegistered() throws InterruptedException {
        assertThat(both.await(20, TimeUnit.SECONDS)).as("both calls audited").isTrue();
        assertThat(events).hasSize(2).allSatisfy(e -> {
            assertThat(e.policyDecision()).startsWith("DENY:");
            assertThat(e.entityType()).isEqualTo(REGISTERED).isNotEqualTo(AuditedEntityTypes.UNREGISTERED);
        });
        assertThat(events).extracting(AuditEvent::tool).containsExactlyInAnyOrder(TOOLS);
    }

    @Test
    @DisplayName("stdio delivers the caller's AuditedEntityTypes to both tools on a DENY path")
    void stdioDeliversTheRegistry() throws Exception {
        PipedOutputStream feed = new PipedOutputStream();
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        McpSyncServer server = null;
        try {
            System.setIn(new PipedInputStream(feed));
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            server = DataPrismMcpServer.stdio(new Unreached(), authz(), scopes(), UNPRIVILEGED, true, false,
                    PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission()
                            .entityTypes(AuditedEntityTypes.of(List.of(REGISTERED))).build());
            feed.write((INIT + "\n" + INITIALIZED + "\n").getBytes(StandardCharsets.UTF_8));
            for (String tool : TOOLS) {
                feed.write((call(tool) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            feed.flush();
            assertDenyAuditedAsRegistered();
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            if (server != null) {
                server.closeGracefully();
            }
            feed.close();
        }
    }

    @Test
    @DisplayName("streamableHttp delivers the caller's AuditedEntityTypes to both tools on a DENY path")
    void streamableHttpDeliversTheRegistry() throws Exception {
        DataPrismMcpServer.HttpTransport http = DataPrismMcpServer.streamableHttp(new Unreached(), authz(), scopes(),
                request -> McpTransportContext.create(
                        Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, UNPRIVILEGED)),
                "/mcp", PrivacyMetrics.none(), audit(), FIXED, ToolOptions.defaults().noAdmission()
                        .entityTypes(AuditedEntityTypes.of(List.of(REGISTERED))).build());
        try {
            AtomicReference<String> session = new AtomicReference<>();
            post(http, INIT, session);
            post(http, INITIALIZED, session);
            for (String tool : TOOLS) {
                post(http, call(tool), session);
            }
            assertDenyAuditedAsRegistered();
        } finally {
            http.server().closeGracefully();
        }
    }

    private static void post(DataPrismMcpServer.HttpTransport http, String body, AtomicReference<String> session)
            throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader(anyString())).thenAnswer(inv -> switch (((String) inv.getArgument(0)).toLowerCase()) {
            case "accept" -> "application/json, text/event-stream";
            case "content-type" -> "application/json";
            case "mcp-session-id" -> session.get();
            default -> null;
        });
        when(request.getHeaderNames()).thenAnswer(inv -> java.util.Collections.enumeration(List.of("Accept", "Content-Type", "Mcp-Session-Id")));
        when(request.getHeaders(anyString())).thenAnswer(inv -> {
            String value = request.getHeader(inv.getArgument(0));
            return java.util.Collections.enumeration(value == null ? List.<String>of() : List.of(value));
        });
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        when(request.getInputStream()).thenReturn(servletStream(body));
        when(request.startAsync()).thenReturn(mock(AsyncContext.class));
        when(request.getContentType()).thenReturn("application/json");
        when(request.getCharacterEncoding()).thenReturn("UTF-8");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        doAnswer(inv -> {
            if ("Mcp-Session-Id".equalsIgnoreCase(inv.getArgument(0))) {
                session.set(inv.getArgument(1));
            }
            return null;
        }).when(response).setHeader(anyString(), any());
        http.transportProvider().service(request, response);
    }

    private static ServletInputStream servletStream(String body) {
        ByteArrayInputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }

            @Override
            public int read() {
                return in.read();
            }
        };
    }

    /** The DENY path is decided before the orchestrator; reaching it is a bug. */
    private static final class Unreached implements ContextOrchestrator {
        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            throw new AssertionError("orchestrator must not be reached on a DENY path");
        }
    }
}
