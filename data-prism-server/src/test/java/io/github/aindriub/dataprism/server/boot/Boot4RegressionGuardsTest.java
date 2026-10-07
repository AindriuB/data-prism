package io.github.aindriub.dataprism.server.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.server.operator.OperatorHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 142: what Spring Boot 4 moved (Jackson 2 converters, actuator JSON, the error path) still
 * behaves as before, on the real server. Boot 4 would otherwise let each of these change silently.
 */
class Boot4RegressionGuardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    void mvcJsonRunsOnTheJackson2Converter() {
        var converters = app.context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();

        assertThat(converters).anyMatch(MappingJackson2HttpMessageConverter.class::isInstance);
        // Boot's Jackson 2 auto-configuration (spring-boot-jackson2) supplies the mapper the converter
        // uses; without it MVC falls back to a mapper of its own that no Boot property reaches.
        var mappers = app.context.getBeansOfType(ObjectMapper.class).values();
        assertThat(mappers).isNotEmpty();
        assertThat(converters).filteredOn(MappingJackson2HttpMessageConverter.class::isInstance)
                .anySatisfy(converter -> assertThat(mappers).anyMatch(mapper ->
                        mapper == ((MappingJackson2HttpMessageConverter) converter).getObjectMapper()));
        for (HttpMessageConverter<?> converter : converters) {
            assertThat(converter.getClass().getName()).doesNotStartWith("tools.jackson");
            assertThat(converter.getClass().getSimpleName()).isNotEqualTo("JacksonJsonHttpMessageConverter");
        }
    }

    @Test
    void jackson3IsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("tools.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void actuatorHealthIsJsonWithTheAuditIntegrityComponent() throws Exception {
        HttpResponse<String> response = Boot4Servers.get(managementPort, "/health", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).containsIgnoringCase("json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("components").path("auditIntegrity").path("status").asText()).isEqualTo("UP");
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
        assertThat(mcpBody.path("path").asText()).isEqualTo("/health");

        HttpResponse<String> operator = server.operator("GET", "/operator/fail", server.operatorToken("guard-op"),
                null);
        assertThat(operator.statusCode()).as(operator.body()).isEqualTo(500);
        assertThat(JSON.readTree(operator.body())).isEqualTo(JSON.readTree("{\"code\":\"OPERATOR_ERROR\"}"));
    }
}
