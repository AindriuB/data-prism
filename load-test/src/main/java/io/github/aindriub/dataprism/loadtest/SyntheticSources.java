package io.github.aindriub.dataprism.loadtest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One HTTPS listener standing in for every generated source: {@code /<source>/parcels/<subject>} answers with
 * a small synthetic parcel record after the source's sampled latency. Every value is invented; the email
 * domain is {@code example.invalid}.
 */
final class SyntheticSources implements AutoCloseable {

    private static final String[] STATUSES = {"IN_TRANSIT", "AT_DEPOT", "OUT_FOR_DELIVERY", "DELIVERED"};

    private final HttpsServer server;
    private final long seed;
    private final Map<String, Served> sources = new HashMap<>();

    private record Served(SourceDefinition definition, int index, AtomicLong calls) { }

    SyntheticSources(List<SourceDefinition> definitions, long seed, SSLContext tls) throws IOException {
        this.seed = seed;
        for (int i = 0; i < definitions.size(); i++) {
            SourceDefinition definition = definitions.get(i);
            sources.put(definition.name(), new Served(definition, i + 1, new AtomicLong()));
        }
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 4096);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", this::handle);
        // Virtual threads: a sleeping source holds no platform thread, so latency is the sampled latency.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String baseUrl() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            // "", "<source>", "parcels", "<subject>"
            Served served = parts.length == 4 && "parcels".equals(parts[2]) ? sources.get(parts[1]) : null;
            if (served == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            try {
                Thread.sleep(served.definition().sampleLatencyMs(seed, served.calls().getAndIncrement()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] body = record(served, parts[3]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /**
     * Sources deliberately differ: status and recipient vary by source index, so the privacy layer has real
     * disagreements to report between sources rather than N identical records.
     */
    private static String record(Served served, String subject) {
        int variant = Math.floorMod(subject.hashCode() + served.index(), 3);
        return "{\"id\":\"" + safe(subject) + "\","
                + "\"status\":\"" + STATUSES[Math.floorMod(subject.hashCode() + served.index() / 2, STATUSES.length)] + "\","
                + "\"depot\":\"DEPOT-" + String.format("%04d", served.index()) + "\","
                + "\"recipientName\":\"Synthetic Recipient " + (variant + 1) + "\","
                + "\"contactEmail\":\"recipient." + (variant + 1) + "@example.invalid\"}";
    }

    private static String safe(String subject) {
        return subject.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
