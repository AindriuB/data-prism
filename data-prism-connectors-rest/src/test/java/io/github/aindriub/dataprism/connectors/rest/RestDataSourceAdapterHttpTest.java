package io.github.aindriub.dataprism.connectors.rest;

import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.core.DataRequest;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against a real HTTP server.
 *
 * <p>Everything else about the connector is tested by inspecting the URI it would
 * build, which proves the encoding but not that a request is ever made. This runs
 * the JDK's own server on a loopback port — no container, no new dependency — so
 * the wiring is exercised rather than assumed.
 */
class RestDataSourceAdapterHttpTest {

    private HttpServer server;
    private final List<String> pathsRequested = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", exchange -> {
            pathsRequested.add(exchange.getRequestURI().getRawPath());
            String id = exchange.getRequestURI().getPath().substring("/customers/".length());

            byte[] body = ("{\"customerId\":\"" + id + "\",\"status\":\"ACTIVE\"}")
                    .getBytes(StandardCharsets.UTF_8);
            if (id.equals("absent")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (id.equals("broken")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
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

    private RestDataSourceAdapter<String> adapter() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(2)),
                RestClient.create(), String.class);
    }

    @Test
    @DisplayName("a configured source is fetched over HTTP")
    void fetchesOverHttp() {
        String body = adapter().fetch(DataRequest.of("CUSTOMER", "123"));

        assertThat(body).contains("\"customerId\":\"123\"");
        assertThat(pathsRequested).containsExactly("/customers/123");
    }

    @Test
    @DisplayName("a 404 means this source holds nothing, not that the request failed")
    void notFoundIsNoData() {
        // A person existing in some systems and not others is ordinary. Treating
        // it as a failure would open the breaker on a perfectly healthy source.
        assertThat(adapter().fetch(DataRequest.of("CUSTOMER", "absent"))).isNull();
    }

    @Test
    @DisplayName("any other error propagates so the fan-out can record and count it")
    void serverErrorPropagates() {
        assertThatThrownBy(() -> adapter().fetch(DataRequest.of("CUSTOMER", "broken")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a traversal attempt reaches the server as one encoded segment")
    void traversalDoesNotEscapeOverTheWire() {
        // The unit test proves what the URI looks like; this proves the server
        // agrees, which is where an encoding mistake would actually show up.
        try {
            adapter().fetch(DataRequest.of("CUSTOMER", "../../admin"));
        } catch (RuntimeException expected) {
            // The path does not exist on the stub; what matters is where it went.
        }
        // The property is that the slash arrived encoded, so it never became a
        // path separator: the server sees one segment under /customers, not a
        // request that climbed out of it. Asserting on the decoded form would be
        // wrong, since those characters legitimately appear in a single segment.
        assertThat(pathsRequested).isNotEmpty().allSatisfy(raw -> assertThat(raw)
                .startsWith("/customers/")
                .contains("%2F")
                .doesNotContain("/../"));
    }
}
