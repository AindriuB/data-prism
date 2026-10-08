package io.github.aindriub.dataprism.connectors.rest;

import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.SourceCallContext;
import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** A preset header of the configured name on the client never reaches the wire unless it is the validated id. */
class OutboundCorrelationDefaultHeaderTest {

    private static final String HEADER = "X-Correlation-ID";
    private static final String PRESET = "preset-synthetic-value";

    private HttpServer server;
    private final Map<String, List<String>> headerByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> traceparentByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            headerByPath.put(path, exchange.getRequestHeaders().getOrDefault(HEADER, List.of()));
            traceparentByPath.put(path, exchange.getRequestHeaders().getOrDefault("traceparent", List.of()));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RestDataSourceAdapter<String> adapter(String configured, String presetName) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        RestClient client = RestClient.builder().defaultHeader(presetName, PRESET).build();
        return new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), false, configured),
                client, String.class);
    }

    private static ExternalCorrelationId opaque(String value) {
        return CorrelationIdPolicy.opaque(CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN).validate(value).orElseThrow();
    }

    @Test
    @DisplayName("no id attached: the preset header is stripped")
    void noIdStripsPreset() {
        adapter(HEADER, HEADER).fetch(DataRequest.of("CUSTOMER", "1"));
        assertThat(headerByPath.get("/customers/1")).isEmpty();
    }

    @Test
    @DisplayName("id rejected by the policy: the preset header is stripped")
    void rejectedIdStripsPreset() {
        assertThat(CorrelationIdPolicy.opaque(CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN).validate("bad id!"))
                .isEmpty();
        adapter(HEADER, HEADER).fetch(DataRequest.of("CUSTOMER", "2"));
        assertThat(headerByPath.get("/customers/2")).isEmpty();
    }

    @Test
    @DisplayName("validated opaque id: exactly the id is sent, not the preset")
    void validatedIdReplacesPreset() {
        adapter(HEADER, HEADER).fetch(DataRequest.of("CUSTOMER", "3")
                .withContext(SourceCallContext.of(opaque("abcdef0123456789abcd"))));
        assertThat(headerByPath.get("/customers/3")).hasSize(1)
                .containsExactly("abcdef0123456789abcd").doesNotContain(PRESET);
    }

    @Test
    @DisplayName("the preset header name in different letter case is stripped too")
    void differentCasePresetIsStripped() {
        adapter(HEADER, "x-correlation-id").fetch(DataRequest.of("CUSTOMER", "4"));
        assertThat(headerByPath.get("/customers/4")).isEmpty();
        adapter(HEADER, "x-correlation-id").fetch(DataRequest.of("CUSTOMER", "5")
                .withContext(SourceCallContext.of(opaque("abcdef0123456789abcd"))));
        assertThat(headerByPath.get("/customers/5")).containsExactly("abcdef0123456789abcd");
    }

    @Test
    @DisplayName("traceparent mode: one child traceparent with an id, none without")
    void traceparentMode() {
        String inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        ExternalCorrelationId id = CorrelationIdPolicy.traceparent().validate(inbound).orElseThrow();
        adapter("traceparent", "traceparent").fetch(DataRequest.of("CUSTOMER", "6")
                .withContext(SourceCallContext.of(id)));
        assertThat(traceparentByPath.get("/customers/6")).hasSize(1).doesNotContain(PRESET, inbound);
        assertThat(traceparentByPath.get("/customers/6").get(0))
                .matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01");
        adapter("traceparent", "traceparent").fetch(DataRequest.of("CUSTOMER", "7"));
        assertThat(traceparentByPath.get("/customers/7")).isEmpty();
    }
}
