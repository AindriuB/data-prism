package io.github.aindriub.dataprism.server.boot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.aindriub.dataprism.server.operator.OperatorHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 142: what Spring Boot 4 moved (Jackson 3 converters, actuator JSON, the error path) still
 * behaves as before, on the real server. Boot 4 would otherwise let each of these change silently.
 * The server is on a single Jackson major, Jackson 3 (J3-5): the Jackson 3 converter is the one in
 * use, and no Jackson 2 databind is on the classpath.
 */
class Boot4RegressionGuardsTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path tempDir;
    static OperatorHarness app;
    static int managementPort;

    @BeforeAll
    static void start() throws Exception {
        Path audit = Files.createDirectories(tempDir.resolve("audit"));
        managementPort = Boot4Servers.freePort();
        app = Boot4Servers.start(tempDir,
                "--management.server.port=" + managementPort,
                // The server chain only lets GET /health through unauthenticated, so the actuator
                // is mounted there, on its own port, instead of under /actuator.
                "--management.endpoints.web.base-path=/",
                "--boot4.guard.failing-controller=true",
                "--dataprism.audit.directory=" + audit,
                "--dataprism.audit.checkpoint.file-path=" + tempDir.resolve("checkpoint.log"),
                "--management.endpoint.health.access=read-only",
                "--management.endpoints.web.exposure.include=health",
                "--management.endpoint.health.show-components=always");
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void mvcJsonRunsOnTheJackson3Converter() {
        var converters = app.context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();

        assertThat(converters).anyMatch(JacksonJsonHttpMessageConverter.class::isInstance);
        for (HttpMessageConverter<?> converter : converters) {
            assertThat(converter.getClass().getSimpleName()).isNotEqualTo("MappingJackson2HttpMessageConverter");
            assertThat(converter.getClass().getName()).doesNotStartWith("org.springframework.http.converter.json.MappingJackson2");
        }
    }

    @Test
    void jackson2DatabindIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void actuatorHealthIsJsonWithTheAuditIntegrityComponent() throws Exception {
        HttpResponse<String> response = Boot4Servers.get(managementPort, "/health", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).containsIgnoringCase("json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("components").path("auditIntegrity").path("status").asString()).isEqualTo("UP");
    }

    @Test
    void anMvcErrorOnTheMcpPortIsBootsJsonAndTheOperatorPortGivesOnlyACode() throws Exception {
        assertMcpPortBootBodyAndOperatorCodeBody(app);
    }

    static void assertMcpPortBootBodyAndOperatorCodeBody(OperatorHarness server) throws Exception {
        HttpResponse<String> mcp = Boot4Servers.mcpGet(server, "/health?fail", null);
        JsonNode mcpBody = JSON.readTree(mcp.body());
        assertThat(mcp.statusCode()).as(mcp.body()).isEqualTo(500);
        assertThat(mcpBody.path("status").asInt()).as(mcp.body()).isEqualTo(500);
        assertThat(mcpBody.path("path").asString()).isEqualTo("/health");

        HttpResponse<String> operator = server.operator("GET", "/operator/fail", server.operatorToken("guard-op"),
                null);
        assertThat(operator.statusCode()).as(operator.body()).isEqualTo(500);
        assertThat(JSON.readTree(operator.body())).isEqualTo(JSON.readTree("{\"code\":\"OPERATOR_ERROR\"}"));
    }
}
