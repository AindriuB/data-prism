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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The correlation header on the wire, against a local stub that records what it saw per path. */
class OutboundCorrelationHttpTest {

    private static final String HEADER = "X-Correlation-ID";

    private HttpServer server;
    private final Map<String, List<String>> headerByPath = new ConcurrentHashMap<>();
    private final Map<String, String> traceparentByPath = new ConcurrentHashMap<>();
    private final Map<String, String> rawQueryByPath = new ConcurrentHashMap<>();
    private final CountDownLatch bothArrived = new CountDownLatch(2);
    private volatile boolean holdForPeer;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            headerByPath.put(path, exchange.getRequestHeaders().getOrDefault(HEADER, List.of()));
            String tp = exchange.getRequestHeaders().getFirst("traceparent");
            if (tp != null) {
                traceparentByPath.put(path, tp);
            }
            rawQueryByPath.put(path, String.valueOf(exchange.getRequestURI().getRawQuery()));
            if (holdForPeer) {
                bothArrived.countDown();
                try {
                    bothArrived.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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

    private RestDataSourceAdapter<String> adapter(String header) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), false, header),
                RestClient.create(), String.class);
    }

    private static ExternalCorrelationId opaque(String value) {
        return CorrelationIdPolicy.opaque(CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN).validate(value).orElseThrow();
    }

    private static DataRequest request(String subject, ExternalCorrelationId id) {
        return DataRequest.of("CUSTOMER", subject).withContext(SourceCallContext.of(id));
    }

    @Test
    @DisplayName("a source with correlation-header receives the id")
    void sendsId() {
        adapter(HEADER).fetch(request("1", opaque("abcdef0123456789abcd")));
        assertThat(headerByPath.get("/customers/1")).containsExactly("abcdef0123456789abcd");
    }

    @Test
    @DisplayName("a source without the key receives no such header")
    void noHeaderWhenNotConfigured() {
        adapter(null).fetch(request("2", opaque("abcdef0123456789abcd")));
        assertThat(headerByPath.get("/customers/2")).isEmpty();
    }

    @Test
    @DisplayName("a call with no id sends no such header")
    void noHeaderWhenNoId() {
        adapter(HEADER).fetch(DataRequest.of("CUSTOMER", "3"));
        assertThat(headerByPath.get("/customers/3")).isEmpty();
    }

    @Test
    @DisplayName("traceparent mode sends a valid child traceparent with the inbound trace-id and a fresh parent-id")
    void traceparentMode() {
        String inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        ExternalCorrelationId id = CorrelationIdPolicy.traceparent().validate(inbound).orElseThrow();
        RestDataSourceAdapter<String> adapter = adapter("traceparent");
        adapter.fetch(request("4", id));
        adapter.fetch(request("5", id));
        String first = traceparentByPath.get("/customers/4");
        String second = traceparentByPath.get("/customers/5");
        assertThat(first).matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01")
                .isNotEqualTo(inbound);
        assertThat(second).matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("the id is never added to the URL or query string")
    void idNotInUrl() {
        adapter(HEADER).fetch(request("6", opaque("abcdef0123456789abcd")));
        assertThat(headerByPath).containsKey("/customers/6");
        assertThat(rawQueryByPath.get("/customers/6")).isEqualTo("null");
    }

    @Test
    @DisplayName("an existing value of the header is replaced, so it is sent at most once")
    void replacesExistingValue() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        RestClient preset = RestClient.builder().defaultHeader(HEADER, "stale").build();
        new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), false, HEADER),
                preset, String.class).fetch(request("7", opaque("fedcba9876543210fedc")));
        assertThat(headerByPath.get("/customers/7")).containsExactly("fedcba9876543210fedc");
    }

    @Test
    @DisplayName("two concurrent fetches through one adapter each send their own id")
    void concurrentFetchesKeepTheirOwnId() throws Exception {
        holdForPeer = true;
        RestDataSourceAdapter<String> adapter = adapter(HEADER);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> a = CompletableFuture.supplyAsync(
                    () -> adapter.fetch(request("a", opaque("aaaaaaaaaaaaaaaaaaaa"))), executor);
            CompletableFuture<String> b = CompletableFuture.supplyAsync(
                    () -> adapter.fetch(request("b", opaque("bbbbbbbbbbbbbbbbbbbb"))), executor);
            CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
        }
        assertThat(bothArrived.getCount()).isZero();
        assertThat(headerByPath.get("/customers/a")).containsExactly("aaaaaaaaaaaaaaaaaaaa");
        assertThat(headerByPath.get("/customers/b")).containsExactly("bbbbbbbbbbbbbbbbbbbb");
    }
}
