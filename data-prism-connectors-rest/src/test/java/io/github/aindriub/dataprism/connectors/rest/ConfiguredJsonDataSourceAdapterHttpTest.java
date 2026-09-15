package io.github.aindriub.dataprism.connectors.rest;

import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.FieldMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configured-JSON adapter against a real HTTP server, proving the schema
 * mismatch case in particular: a response that is not the JSON object the
 * catalogue was reviewed against must fail the fetch, not reach the scrubbing
 * engine as if it were empty or partial.
 */
class ConfiguredJsonDataSourceAdapterHttpTest {

    private HttpServer server;
    private String nextBody;
    private int nextStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/customers/".length());
            if (nextStatus == 404) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = (nextBody != null ? nextBody : "{\"customerId\":\"" + id + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ConfiguredJsonDataSourceAdapter adapter() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        RestSource transport = new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(2));
        Map<String, FieldMetadata> fields = Map.of("customerId",
                new FieldMetadata("customerId", true, FieldMetadata.SELF, List.of(),
                        io.github.aindriub.dataprism.annotations.PrivacyNamespace.NONE, null, "", null,
                        String.class, null));
        ConfiguredJsonSource source = new ConfiguredJsonSource(transport, "customer-v1", "customerId", fields);
        return new ConfiguredJsonDataSourceAdapter(source, RestClient.create());
    }

    @Test
    @DisplayName("a JSON object response is fetched and tagged with the source name")
    void fetchesAndTagsTheSourceName() {
        nextBody = "{\"customerId\":\"123\",\"status\":\"ACTIVE\"}";
        ConfiguredJsonPayload payload = adapter().fetch(DataRequest.of("CUSTOMER", "123"));

        assertThat(payload.sourceName()).isEqualTo("customer-api");
        assertThat(payload.body().get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a 404 means no data, exactly as for a Java-first source")
    void notFoundIsNoData() {
        nextStatus = 404;
        assertThat(adapter().fetch(DataRequest.of("CUSTOMER", "absent"))).isNull();
    }

    @Test
    @DisplayName("a JSON array response is a schema mismatch and fails the fetch")
    void arrayResponseIsASchemaMismatch() {
        nextBody = "[{\"customerId\":\"123\"}]";
        // Not a bare RuntimeException: that is satisfied by an unstarted server
        // or an NPE as readily as by the mismatch this test claims to prove.
        // ResourceAccessException (a RestClientException too, thrown when the
        // server cannot be reached at all) has a java.net.ConnectException
        // cause instead, so this assertion fails if the fetch never actually
        // reached a response body to convert.
        assertThatThrownBy(() -> adapter().fetch(DataRequest.of("CUSTOMER", "123")))
                .isInstanceOf(RestClientException.class)
                .hasCauseInstanceOf(HttpMessageNotReadableException.class);
    }

    @Test
    @DisplayName("a bare scalar response is a schema mismatch and fails the fetch")
    void scalarResponseIsASchemaMismatch() {
        nextBody = "\"just a string\"";
        assertThatThrownBy(() -> adapter().fetch(DataRequest.of("CUSTOMER", "123")))
                .isInstanceOf(RestClientException.class)
                .hasCauseInstanceOf(HttpMessageNotReadableException.class);
    }
}
