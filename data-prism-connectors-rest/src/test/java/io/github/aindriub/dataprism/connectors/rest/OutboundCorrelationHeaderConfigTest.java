package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The per-source and global {@code correlation-header} schema: accepted, rejected, overridden. */
class OutboundCorrelationHeaderConfigTest {

    private static InputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String restYaml(String headerLine) {
        return "sources:\n"
                + "  customer-api:\n"
                + "    base-url: http://127.0.0.1:9\n"
                + "    path: /customers/{subject}\n"
                + headerLine;
    }

    private static String jsonYaml(String headerLine) {
        return """
                json-sources:
                  customer-api:
                    base-url: https://customer.example
                    path: /v1/customers/{subject}
                    timeout: PT2S
                    model-version: customer-v1
                    subject-json-path: customerId
                """ + headerLine + """
                    fields:
                      customerId:
                        identifier: true
                """;
    }

    @Test
    @DisplayName("both schemas accept an optional per-source correlation-header")
    void acceptsHeader() {
        assertThat(RestSources.fromYaml(stream(restYaml("    correlation-header: X-Correlation-ID\n")))
                .sources().get("customer-api").correlationHeader()).isEqualTo("X-Correlation-ID");
        assertThat(ConfiguredJsonSources.fromYaml(stream(jsonYaml("    correlation-header: X-Correlation-ID\n")))
                .sources().get("customer-api").transport().correlationHeader()).isEqualTo("X-Correlation-ID");
        assertThat(RestSources.fromYaml(stream(restYaml(""))).sources().get("customer-api").correlationHeader())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "Proxy-Authorization", "Cookie", "Host", "Content-Length",
            "Transfer-Encoding", "Forwarded", "authorization", "COOKIE"})
    @DisplayName("a forbidden header name fails startup with the line, in both schemas")
    void rejectsForbiddenNames(String name) {
        assertThatThrownBy(() -> RestSources.fromYaml(stream(restYaml("    correlation-header: " + name + "\n"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CORRELATION_HEADER")
                .hasMessageContaining("line 5");
        assertThatThrownBy(() -> ConfiguredJsonSources.fromYaml(
                stream(jsonYaml("    correlation-header: " + name + "\n"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CORRELATION_HEADER")
                .hasMessageContaining("line 8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"X Correlation\"", "\"X:Id\"", "\"X-Id\\n\"", "\"\"", "\"Bad(Header)\""})
    @DisplayName("a name that is not an RFC 9110 token fails startup")
    void rejectsNonTokens(String quoted) {
        assertThatThrownBy(() -> RestSources.fromYaml(stream(restYaml("    correlation-header: " + quoted + "\n"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CORRELATION_HEADER");
    }

    @Test
    @DisplayName("a source without its own header uses the global default; its own wins; neither sends nothing")
    void globalDefaultAndOverride() {
        assertThat(RestSources.fromYaml(stream(restYaml("")), "X-Global").sources().get("customer-api")
                .correlationHeader()).isEqualTo("X-Global");
        assertThat(RestSources.fromYaml(stream(restYaml("    correlation-header: X-Own\n")), "X-Global")
                .sources().get("customer-api").correlationHeader()).isEqualTo("X-Own");
        assertThat(RestSources.fromYaml(stream(restYaml("")), null).sources().get("customer-api")
                .correlationHeader()).isNull();
        assertThat(ConfiguredJsonSources.fromYaml(stream(jsonYaml("")), false, "X-Global").sources()
                .get("customer-api").transport().correlationHeader()).isEqualTo("X-Global");
        assertThat(ConfiguredJsonSources.fromYaml(stream(jsonYaml("    correlation-header: X-Own\n")), false, "X-Global")
                .sources().get("customer-api").transport().correlationHeader()).isEqualTo("X-Own");
        assertThat(ConfiguredJsonSources.fromYaml(stream(jsonYaml("")), false, null).sources()
                .get("customer-api").transport().correlationHeader()).isNull();
    }

    @Test
    @DisplayName("an invalid global default fails with INVALID_CORRELATION_HEADER")
    void invalidGlobalDefault() {
        assertThatThrownBy(() -> RestSources.fromYaml(stream(restYaml("")), "Cookie"))
                .hasMessageContaining("INVALID_CORRELATION_HEADER");
        assertThatThrownBy(() -> ConfiguredJsonSources.fromYaml(stream(jsonYaml("")), false, "Bad Header"))
                .hasMessageContaining("INVALID_CORRELATION_HEADER");
    }

    @Test
    @DisplayName("the initializer binds dataprism.correlation.outbound.header and validates it")
    void initializerReadsGlobal() throws Exception {
        Path file = Files.createTempFile("json-sources", ".yaml");
        try {
            Files.writeString(file, jsonYaml(""));
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                    "dataprism.json-sources.config-location", file.toUri().toString(),
                    "dataprism.correlation.outbound.header", "X-Global")));
            assertThat(ConfiguredJsonSourcesInitializer.loadConfig(env).sources().get("customer-api")
                    .transport().correlationHeader()).isEqualTo("X-Global");

            StandardEnvironment bad = new StandardEnvironment();
            bad.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                    "dataprism.json-sources.config-location", file.toUri().toString(),
                    "dataprism.correlation.outbound.header", "Authorization")));
            assertThatThrownBy(() -> ConfiguredJsonSourcesInitializer.loadConfig(bad))
                    .hasMessageContaining("INVALID_CORRELATION_HEADER");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
