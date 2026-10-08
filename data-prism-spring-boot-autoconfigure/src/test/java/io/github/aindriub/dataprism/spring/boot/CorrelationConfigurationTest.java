package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.correlation.InboundCorrelation;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import tools.jackson.databind.json.JsonMapper;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 113: {@code dataprism.correlation.*} bound, refused and delivered into the transport context. */
class CorrelationConfigurationTest {

    private static final String GOOD_ID = "0123456789abcdef0123456789abcdef";
    private static final String REJECTED_TEXT = "jane.doe@example.invalid";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(AuditRetentionConfigurationTest.Integrations.class)
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=slf4j");
    }

    private static void assertRefusedWith(WebApplicationContextRunner r, String code) {
        r.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
            assertThat(((DataPrismConfigurationException) failure).code()).isEqualTo(code);
        });
    }

    @Test
    void the_feature_is_off_and_the_defaults_are_bound_when_nothing_is_set() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            DataPrismProperties.Correlation.Inbound inbound =
                    context.getBean(DataPrismProperties.class).getCorrelation().getInbound();
            assertThat(inbound.getHeader()).isNull();
            assertThat(inbound.getFormat()).isEqualTo("opaque");
            assertThat(inbound.getPattern()).isNull();
            assertThat(inbound.isRequired()).isFalse();
            assertThat(context.getBean(DataPrismProperties.class).getCorrelation().getOutbound().getHeader())
                    .isNull();
        });
    }

    @Test
    void every_property_is_bound() {
        runner().withPropertyValues("dataprism.correlation.inbound.header=X-Correlation-ID",
                "dataprism.correlation.inbound.format=opaque",
                "dataprism.correlation.inbound.pattern=[a-z]{4}",
                "dataprism.correlation.inbound.required=true",
                "dataprism.correlation.outbound.header=X-Downstream-ID").run(context -> {
            assertThat(context).hasNotFailed();
            DataPrismProperties.Correlation c = context.getBean(DataPrismProperties.class).getCorrelation();
            assertThat(c.getInbound().getHeader()).isEqualTo("X-Correlation-ID");
            assertThat(c.getInbound().getPattern()).isEqualTo("[a-z]{4}");
            assertThat(c.getInbound().isRequired()).isTrue();
            assertThat(c.getOutbound().getHeader()).isEqualTo("X-Downstream-ID");
        });
    }

    @Test
    void a_header_that_is_not_a_token_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.header=X Correlation"),
                "INVALID_CORRELATION_HEADER");
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.header=X-Id:"),
                "INVALID_CORRELATION_HEADER");
    }

    @Test
    void a_credential_header_is_refused_whatever_its_case() {
        for (String name : new String[] {"Authorization", "cookie", "Proxy-Authorization"}) {
            assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.header=" + name),
                    "INVALID_CORRELATION_HEADER");
        }
    }

    @Test
    void an_invalid_outbound_header_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.outbound.header=Authorization"),
                "INVALID_CORRELATION_HEADER");
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.outbound.header=bad header"),
                "INVALID_CORRELATION_HEADER");
    }

    @Test
    void a_pattern_that_does_not_compile_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.header=X-Correlation-ID",
                "dataprism.correlation.inbound.pattern=[a-z"), "INVALID_CORRELATION_PATTERN");
    }

    @Test
    void a_pattern_with_the_traceparent_format_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.header=traceparent",
                "dataprism.correlation.inbound.format=traceparent",
                "dataprism.correlation.inbound.pattern=[a-z]+"), "CORRELATION_PATTERN_NOT_APPLICABLE");
    }

    @Test
    void an_unknown_format_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.header=X-Correlation-ID",
                "dataprism.correlation.inbound.format=uuid"), "INVALID_CORRELATION_FORMAT");
    }

    @Test
    void required_without_a_header_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.inbound.required=true"),
                "CORRELATION_REQUIRED_WITHOUT_HEADER");
    }

    @Test
    void required_without_an_http_transport_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.transport.mode=stdio",
                "dataprism.transport.fixture-development=true",
                "dataprism.correlation.inbound.header=X-Correlation-ID",
                "dataprism.correlation.inbound.required=true"), "CORRELATION_REQUIRES_HTTP_TRANSPORT");
    }

    private static DataPrismProperties properties(String... pairs) {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getCorrelation().getInbound().setHeader("X-Correlation-ID");
        for (int i = 0; i < pairs.length; i += 2) {
            if ("format".equals(pairs[i])) {
                properties.getCorrelation().getInbound().setFormat(pairs[i + 1]);
            }
        }
        properties.getSecurity().getCallerClaims().setPrincipal("sub");
        properties.getSecurity().getCallerClaims().setRoles("roles");
        properties.getSecurity().getCallerClaims().setInvestigation("case_id");
        return properties;
    }

    private static void authenticate() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject("synthetic-principal")
                .claim("azp", "synthetic-client").claim("roles", List.of("investigator"))
                .claim("purpose", "investigation").claim("case_id", "CASE-1")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MockHttpServletRequest request(String... values) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (String value : values) {
            request.addHeader("X-Correlation-ID", value);
        }
        return request;
    }

    @Test
    void the_extractor_puts_a_valid_id_under_the_correlation_key() {
        authenticate();
        McpTransportContext context = new JwtCallerContextExtractor(properties()).extract(request(GOOD_ID));

        assertThat(context.get(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY)).isNotNull();
        Object value = context.get(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY);
        assertThat(value).isInstanceOf(InboundCorrelation.class);
        assertThat(((InboundCorrelation) value).id().orElseThrow().toString()).doesNotContain("synthetic-");
        assertThat(((InboundCorrelation) value).isPresent()).isTrue();
    }

    @Test
    void the_extractor_reports_an_absent_header_as_absent() {
        authenticate();
        McpTransportContext context = new JwtCallerContextExtractor(properties()).extract(request());
        assertThat(((InboundCorrelation) context.get(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY))
                .isAbsent()).isTrue();
    }

    @Test
    void the_extractor_adds_nothing_when_no_header_is_configured() {
        authenticate();
        DataPrismProperties off = properties();
        off.getCorrelation().getInbound().setHeader(null);
        McpTransportContext context = new JwtCallerContextExtractor(off).extract(request(GOOD_ID));
        assertThat((Object) context.get(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY)).isNull();
    }

    @Test
    void a_rejected_value_is_logged_as_a_code_and_the_text_never_appears() {
        authenticate();
        Logger logger = (Logger) LoggerFactory.getLogger(JwtCallerContextExtractor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            McpTransportContext context = new JwtCallerContextExtractor(properties()).extract(request(REJECTED_TEXT));
            assertThat(((InboundCorrelation) context.get(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY))
                    .isRejected()).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("EXTERNAL_CORRELATION_ID_DROPPED"));
        assertThat(appender.list).noneSatisfy(e -> {
            assertThat(e.getFormattedMessage()).contains(REJECTED_TEXT);
            assertThat(e.getArgumentArray() == null ? "" : java.util.Arrays.toString(e.getArgumentArray()))
                    .contains(REJECTED_TEXT);
        });
    }

    @Test
    void a_repeated_header_is_rejected() {
        authenticate();
        McpTransportContext context = new JwtCallerContextExtractor(properties())
                .extract(request(GOOD_ID, GOOD_ID));
        assertThat(((InboundCorrelation) context.get(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY))
                .isRejected()).isTrue();
    }

    @Test
    void the_traceparent_format_reads_the_trace_id() {
        authenticate();
        McpTransportContext context = new JwtCallerContextExtractor(properties("format", "traceparent"))
                .extract(request("00-" + GOOD_ID + "-0123456789abcdef-01"));
        InboundCorrelation correlation =
                (InboundCorrelation) context.get(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY);
        assertThat(correlation.isPresent()).isTrue();
    }

    // --- the Spring-built server, driven through its own servlet --------------------------------

    private static final List<AuditEvent> AUDITED = new CopyOnWriteArrayList<>();
    private static final List<ContextRequest> INVOKED = new CopyOnWriteArrayList<>();

    /** The application's side: adapters, keys, a recording audit sink and a stub orchestrator, and the real extractor. */
    @Configuration(proxyBeanMethods = false)
    static class ServedIntegrations {
        @Bean DataSourceAdapter<String> customerAdapter() { return new AuditRetentionConfigurationTest.Integrations().customerAdapter(); }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return new AuditRetentionConfigurationTest.Integrations().keys(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean AuditSink recordingAuditSink() { return AUDITED::add; }
        /** Stands in for the orchestrator's one ALLOW record, so the id the tool hands it is what gets audited. */
        @Bean ContextOrchestrator stubOrchestrator(AuditRecorder recorder) {
            return (request, privacyContext, investigationContext) -> {
                INVOKED.add(request);
                recorder.record(new AuditEntry(investigationContext.principalId(), investigationContext.clientId(),
                        request.toolName(), request.entityType(), "SUBJ-STUB", "fingerprint", "DEFAULT",
                        privacyContext.scopeId(), privacyContext.purpose(), investigationContext.caseId(), "ALLOW",
                        Set.of("customer"), Set.of(), "stub-correlation", Map.of(), null, null,
                        request.externalCorrelationId().map(ExternalCorrelationId::value).orElse("")));
                return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(), List.of(),
                        JsonMapper.builder().build().createObjectNode(), Map.of(), "stub-correlation");
            };
        }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor(DataPrismProperties properties) {
            return new JwtCallerContextExtractor(properties);
        }
    }

    private static WebApplicationContextRunner served() {
        AUDITED.clear();
        INVOKED.clear();
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ServedIntegrations.class)
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=approved-sink",
                        "dataprism.correlation.inbound.header=X-Correlation-ID",
                        "dataprism.correlation.inbound.pattern=[a-z]+-[a-z]+-[0-9]{4}",
                        "dataprism.correlation.inbound.required=true");
    }

    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
            + "\"name\":\"get_entity_context\",\"arguments\":{\"entityType\":\"CUSTOMER\",\"subjectId\":\"1\"}}}";

    private static MockHttpServletRequest post(String body, String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setAsyncSupported(true);
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (sessionId != null) {
            request.addHeader("Mcp-Session-Id", sessionId);
            request.addHeader("MCP-Protocol-Version", "2025-06-18");
        }
        return request;
    }

    /** Opens a session on the Spring-built transport, then makes one tool call carrying the given header values. */
    private static String callThroughTheServlet(jakarta.servlet.http.HttpServlet servlet, String... headerValues)
            throws Exception {
        MockHttpServletResponse init = new MockHttpServletResponse();
        servlet.service(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"synthetic-client\",\"version\":\"1\"}}}", null), init);
        String session = init.getHeader("Mcp-Session-Id");
        assertThat(session).as("the initialize response: " + init.getContentAsString()).isNotNull();
        servlet.service(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session),
                new MockHttpServletResponse());

        authenticate();
        MockHttpServletRequest call = post(CALL, session);
        for (String value : headerValues) {
            call.addHeader("X-Correlation-ID", value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(call, response);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                .until(() -> response.getContentAsString().contains("\"id\":2"));
        return response.getContentAsString();
    }

    @Test
    void a_spring_built_server_with_required_refuses_a_call_without_the_header() {
        served().run(context -> {
            assertThat(context).hasNotFailed();
            String body = callThroughTheServlet(context.getBean(DataPrismMcpServer.HttpTransport.class)
                    .transportProvider());

            assertThat(body).contains("EXTERNAL_CORRELATION_ID_REQUIRED");
            assertThat(INVOKED).isEmpty();
            assertThat(AUDITED).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("DENY:EXTERNAL_CORRELATION_ID_REQUIRED");
                assertThat(e.externalCorrelationId()).isEmpty();
            });
        });
    }

    @Test
    void a_spring_built_server_records_the_header_value_on_the_allow_event() {
        served().run(context -> {
            assertThat(context).hasNotFailed();
            String body = callThroughTheServlet(context.getBean(DataPrismMcpServer.HttpTransport.class)
                    .transportProvider(), "synthetic-clid-0001");

            assertThat(body).doesNotContain("EXTERNAL_CORRELATION_ID_REQUIRED");
            assertThat(INVOKED).hasSize(1);
            assertThat(AUDITED).singleElement().satisfies(e -> {
                assertThat(e.policyDecision()).isEqualTo("ALLOW");
                assertThat(e.externalCorrelationId()).isEqualTo("synthetic-clid-0001");
            });
        });
    }

    @Test
    void a_spring_built_server_refuses_a_value_the_pattern_rejects() {
        served().run(context -> {
            String body = callThroughTheServlet(context.getBean(DataPrismMcpServer.HttpTransport.class)
                    .transportProvider(), REJECTED_TEXT);

            assertThat(body).contains("EXTERNAL_CORRELATION_ID_INVALID").doesNotContain(REJECTED_TEXT);
            assertThat(INVOKED).isEmpty();
        });
    }
}
