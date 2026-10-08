package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 150: {@code dataprism.audit.entity-types} bound, validated, logged and delivered to the tools. */
class AuditEntityTypesConfigurationTest {

    private static final List<AuditEvent> AUDITED = new CopyOnWriteArrayList<>();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Configuration(proxyBeanMethods = false)
    static class Served {
        @Bean DataSourceAdapter<String> customerAdapter() { return new AuditRetentionConfigurationTest.Integrations().customerAdapter(); }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return new AuditRetentionConfigurationTest.Integrations().keys(); }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean AuditSink recordingAuditSink() { return AUDITED::add; }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor(DataPrismProperties properties) {
            return new JwtCallerContextExtractor(properties);
        }
    }

    private static WebApplicationContextRunner runner(String... extra) {
        AUDITED.clear();
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(Served.class)
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=approved-sink")
                .withPropertyValues(extra);
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

    private static void authenticate() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject("synthetic-principal")
                .claim("azp", "synthetic-client").claim("roles", List.of("investigator"))
                .claim("purpose", "investigation").claim("case_id", "CASE-1")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** One tool call through the Spring-built servlet; unauthenticated calls are refused by the tool itself. */
    private static void callThroughTheServlet(DataPrismMcpServer.HttpTransport transport, String entityType,
                                              boolean authenticated) throws Exception {
        var servlet = transport.transportProvider();
        MockHttpServletResponse init = new MockHttpServletResponse();
        servlet.service(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"synthetic-client\",\"version\":\"1\"}}}", null), init);
        String session = init.getHeader("Mcp-Session-Id");
        assertThat(session).isNotNull();
        servlet.service(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session),
                new MockHttpServletResponse());
        if (authenticated) {
            authenticate();
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"get_entity_context\",\"arguments\":{\"entityType\":\"" + entityType
                + "\",\"subjectId\":\"1\"}}}", session), response);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                .until(() -> response.getContentAsString().contains("\"id\":2"));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> !AUDITED.isEmpty());
    }

    private static String audited(WebApplicationContextRunner r, String entityType, boolean authenticated) {
        AUDITED.clear();
        String[] holder = new String[1];
        r.run(context -> {
            assertThat(context).hasNotFailed();
            callThroughTheServlet(context.getBean(DataPrismMcpServer.HttpTransport.class), entityType, authenticated);
            assertThat(AUDITED).singleElement().satisfies(e -> holder[0] = e.entityType());
        });
        return holder[0];
    }

    @Test
    void the_list_defaults_to_empty() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataPrismProperties.class).getAudit().getEntityTypes()).isEmpty();
        });
    }

    @Test
    void a_valid_list_binds_and_reaches_the_tools_on_both_denial_paths() {
        WebApplicationContextRunner r = runner("dataprism.audit.entity-types[0]=CUSTOMER",
                "dataprism.audit.entity-types[1]=Case-File");
        r.run(context -> assertThat(context.getBean(DataPrismProperties.class).getAudit().getEntityTypes())
                .containsExactly("CUSTOMER", "Case-File"));

        // The tool's own denial (no caller), and the orchestrator's (the adapter returns nothing).
        for (boolean authenticated : new boolean[] {false, true}) {
            assertThat(audited(r, "CUSTOMER", authenticated)).as("authenticated=%s", authenticated)
                    .isEqualTo("CUSTOMER");
            SecurityContextHolder.clearContext();
            // ORDER passes the shape but is not on the list, so the shape fallback is off.
            assertThat(audited(r, "ORDER", authenticated)).as("authenticated=%s", authenticated)
                    .isEqualTo(AuditedEntityTypes.UNREGISTERED);
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void with_no_list_the_shape_fallback_reaches_the_tools_on_both_denial_paths() {
        for (boolean authenticated : new boolean[] {false, true}) {
            assertThat(audited(runner(), "CUSTOMER", authenticated)).isEqualTo("CUSTOMER");
            SecurityContextHolder.clearContext();
            assertThat(audited(runner(), "ACC-1", authenticated)).isEqualTo(AuditedEntityTypes.UNREGISTERED);
            SecurityContextHolder.clearContext();
        }
    }

    private static List<ILoggingEvent> startupLog(WebApplicationContextRunner r) {
        Logger logger = (Logger) LoggerFactory.getLogger(DataPrismAutoConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            r.run(context -> assertThat(context).hasNotFailed());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("dataprism.audit.entity-types"))
                .toList();
    }

    @Test
    void an_unset_list_logs_one_info_line_recommending_the_property() {
        assertThat(startupLog(runner())).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("Set dataprism.audit.entity-types")
                    .contains(AuditedEntityTypes.UNREGISTERED);
        });
    }

    @Test
    void a_set_list_logs_no_such_line() {
        assertThat(startupLog(runner("dataprism.audit.entity-types[0]=CUSTOMER"))).isEmpty();
    }

    @Test
    void each_invalid_shape_is_refused_without_echoing_it() {
        for (String bad : new String[] {"", "1ABC", "A B", AuditedEntityTypes.UNREGISTERED, "A".repeat(65)}) {
            assertRefusedWith(runner("dataprism.audit.entity-types[0]=" + bad), "INVALID_AUDIT_ENTITY_TYPE");
        }
    }

    @Test
    void one_invalid_entry_among_valid_ones_is_refused() {
        assertRefusedWith(runner("dataprism.audit.entity-types[0]=CUSTOMER", "dataprism.audit.entity-types[1]=ACC 1"),
                "INVALID_AUDIT_ENTITY_TYPE");
    }
}
