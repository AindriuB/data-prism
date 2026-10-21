package io.github.aindriub.dataprism.loadtest;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failures as the MCP SDK 2.0.1 client really throws them: a loopback server initialises normally and then answers
 * {@code tools/call} with a chosen HTTP status, and the exception is classified exactly as the driver would.
 */
@Timeout(120)
class SdkFailureCauseTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String callBody = "";

    private McpSyncClient start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (request.contains("\"initialize\"")) {
                    byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(request) + ",\"result\":{\"protocolVersion\":\""
                            + protocolVersion(request) + "\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\","
                            + "\"version\":\"1\"}}}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else if (request.contains("tools/call")) {
                    byte[] body = callBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
                    if (body.length > 0) {
                        exchange.getResponseBody().write(body);
                    }
                } else {
                    exchange.sendResponseHeaders(202, -1);
                }
            }
        });
        server.start();
        return LoadDriver.openClient("http://127.0.0.1:" + server.getAddress().getPort(), () -> "t",
                Duration.ofSeconds(5));
    }

    private static String idOf(String request) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)").matcher(request);
        return m.find() ? m.group(1) : "0";
    }

    private static String protocolVersion(String request) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"protocolVersion\"\\s*:\\s*\"([^\"]*)\"").matcher(request);
        return m.find() ? m.group(1) : "2025-06-18";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private FailureCause causeOf(int httpStatus, String body) throws Exception {
        McpSyncClient client = start();
        status.set(httpStatus);
        callBody = body;
        try {
            client.callTool(new McpSchema.CallToolRequest("get_entity_context", Map.of("subjectId", "x")));
            throw new AssertionError("expected a failure for HTTP " + httpStatus);
        } catch (RuntimeException failure) {
            return FailureCause.of(failure);
        }
    }

    @Test
    void unauthorisedAndForbiddenAreFourXx() throws Exception {
        assertThat(causeOf(401, "")).isEqualTo(FailureCause.HTTP_STATUS_4XX);
        stop();
        assertThat(causeOf(403, "")).isEqualTo(FailureCause.HTTP_STATUS_4XX);
    }

    @Test
    void tooManyRequestsIsFourXx() throws Exception {
        assertThat(causeOf(429, "{\"error\":\"slow down\"}")).isEqualTo(FailureCause.HTTP_STATUS_4XX);
    }

    @Test
    void serverErrorsAreAnUnreportedStatus() throws Exception {
        // The SDK throws "Failed to send message: AggregateResponseEvent[...]" with no status in it.
        assertThat(causeOf(500, "{\"error\":\"boom\"}")).isEqualTo(FailureCause.HTTP_STATUS_UNREPORTED);
        stop();
        assertThat(causeOf(503, "")).isEqualTo(FailureCause.HTTP_STATUS_UNREPORTED);
    }
}
