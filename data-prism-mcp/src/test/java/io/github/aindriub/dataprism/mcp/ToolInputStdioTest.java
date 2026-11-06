package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-invalid input over a real transport: JSON-RPC lines through {@link StdioServerTransportProvider} and
 * {@code syncServer}, so the SDK's own {@code tools/call} wrapper runs. A direct handler call bypasses it,
 * which is how the SDK's validation, before the handlers, went unnoticed.
 */
class ToolInputStdioTest {

    static final String SENTINEL = "SENTINEL-d3adb33f";
    static final Set<String> ALL = EveryToolValidatesInputTest.ALL;

    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        // The SDK logs every inbound message, arguments included, at DEBUG: that is below the level a
        // deployment runs at, and outside this change. INFO and above is what is captured and checked.
        previousLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
    }

    private String loggedText() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            out.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                out.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return out.toString();
    }

    // ---- a JSON-RPC peer ----

    private static final class QueueInput extends InputStream {
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int position;

        void feed(String line) {
            chunks.add((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void end() {
            chunks.add(new byte[0]);
        }

        /** Returns what is there, rather than the default's wait for a full buffer. */
        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            int first = read();
            if (first < 0) {
                return -1;
            }
            into[offset] = (byte) first;
            int n = 1;
            while (n < length && position < current.length) {
                into[offset + n++] = current[position++];
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            while (position >= current.length) {
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                if (current.length == 0) {
                    chunks.add(current);
                    return -1;
                }
                position = 0;
            }
            return current[position++] & 0xFF;
        }
    }

    private static final class LineOutput extends OutputStream {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                lines.add(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            } else {
                buffer.write(b);
            }
        }
    }

    /** What a tool call answered, as the client received it. */
    record Answer(boolean isError, String text, String correlationId, ObjectNode result) {

        /** The whole result with its correlation id removed: what two refusals are compared on. */
        ObjectNode withoutCorrelation() {
            ObjectNode copy = result.deepCopy();
            copy.remove("_meta");
            return copy;
        }
    }

    private static final class Peer implements AutoCloseable {
        private final QueueInput in = new QueueInput();
        private final LineOutput out = new LineOutput();
        private final McpSyncServer server;
        private final tools.jackson.databind.json.JsonMapper mapper;
        private int nextId = 1;

        Peer(DataPrismMcpServer.Wiring wiring) {
            mapper = wiring.mapper();
            server = DataPrismMcpServer.syncServer(wiring,
                    new StdioServerTransportProvider(wiring.json(), in, out));
            request("initialize", Map.of("protocolVersion", "2025-06-18",
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "stdio-test", "version", "1")));
            in.feed("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        JsonNode request(String method, Object params) {
            int id = nextId++;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            in.feed(mapper.writeValueAsString(message));
            try {
                while (true) {
                    String line = out.lines.poll(15, TimeUnit.SECONDS);
                    assertThat(line).as("a response to " + method).isNotNull();
                    JsonNode node = mapper.readTree(line);
                    if (node.has("id") && node.get("id").asInt() == id) {
                        return node;
                    }
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }

        Answer call(String tool, Map<String, Object> arguments) {
            JsonNode response = request("tools/call", Map.of("name", tool, "arguments", arguments));
            assertThat(response.has("error")).as("a tools/call that reaches a handler is not a protocol error")
                    .isFalse();
            ObjectNode result = (ObjectNode) response.get("result");
            String text = result.get("content").get(0).get("text").asString();
            JsonNode meta = result.get("_meta");
            String correlation = meta == null ? null
                    : meta.get(ToolCalls.CORRELATION_ID_META_KEY).asString();
            return new Answer(result.path("isError").asBoolean(false), text, correlation, result);
        }

        @Override
        public void close() {
            in.end();
            server.closeGracefully();
        }
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    private static int count(EveryToolValidatesInputTest.Fixture fx, Metric metric) {
        return fx.env.metrics.counts.getOrDefault(metric, 0);
    }

    // ---- the rows of the table ----

    @Test
    @DisplayName("a schema-invalid call to each tool is TOOL_INPUT_INVALID with one DENY event, counted as denied")
    void invalidCallIsRefusedAuditedAndCounted() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL,
                ListSourcesToolTest.CALLER);
        Map<String, Map<String, Object>> bad = new LinkedHashMap<>();
        bad.put("get_entity_context", args("entityType", 5, "subjectId", "x"));
        bad.put("compare_entity_sources", args("entityType", "CUSTOMER"));
        bad.put("list_sources", args("pageSize", 0));
        bad.put("describe_source", args("source", 5));
        Map<String, String> entityType = Map.of("get_entity_context", "<unregistered>",
                "compare_entity_sources", "<unregistered>", "list_sources", "<unregistered>",
                "describe_source", "");

        try (Peer peer = new Peer(fx.wiring)) {
            for (Map.Entry<String, Map<String, Object>> call : bad.entrySet()) {
                int events = fx.env.audited.size();
                int denied = count(fx, Metric.MCP_DENIED);

                Answer answer = peer.call(call.getKey(), call.getValue());

                assertThat(answer.isError()).as(call.getKey()).isTrue();
                assertThat(answer.text()).as(call.getKey()).isEqualTo("TOOL_INPUT_INVALID");
                assertThat(answer.correlationId()).as(call.getKey()).isNotBlank();
                assertThat(fx.env.audited).as(call.getKey()).hasSize(events + 1);
                AuditEvent event = fx.env.audited.get(events);
                assertThat(event.tool()).isEqualTo(call.getKey());
                assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_INPUT_INVALID");
                assertThat(event.entityType()).isEqualTo(entityType.get(call.getKey()));
                assertThat(event.correlationId()).isEqualTo(answer.correlationId());
                assertThat(count(fx, Metric.MCP_DENIED)).as(call.getKey()).isEqualTo(denied + 1);
                assertThat(count(fx, Metric.MCP_REQUESTS)).as("no admission, no accepted call").isZero();
            }
        }
    }

    @Test
    @DisplayName("an invalid call keeps the reserved names it carried in the audit event")
    void rejectedArgumentsAreKept() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL,
                ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(fx.wiring)) {
            peer.call("list_sources", args("pageSize", 0, "principalId", "x", "approvalId", "y"));
        }

        assertThat(fx.env.audited).singleElement().satisfies(event -> assertThat(event.rejectedArguments())
                .containsExactlyInAnyOrder("principalId", "approvalId"));
    }

    @Test
    @DisplayName("a call with a page key, however invalid, is a forged link: LINK_INVALID, counted once each")
    void invalidPageIsAForgedLink() {
        List<Object> invalidPages = new ArrayList<>();
        invalidPages.add("a".repeat(1025));
        invalidPages.add("");
        invalidPages.add(7);
        invalidPages.add(null);

        Answer forged;
        try (Peer baseline = new Peer(new EveryToolValidatesInputTest.Fixture(ALL, ListSourcesToolTest.CALLER).wiring)) {
            forged = baseline.call("list_sources", args("page", "AAAAAAAAAAAAAAAAAAAAAAAA"));
        }
        assertThat(forged.text()).isEqualTo("The link is not valid.");

        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL,
                ListSourcesToolTest.CALLER);
        try (Peer peer = new Peer(fx.wiring)) {
            for (Object page : invalidPages) {
                int events = fx.env.audited.size();
                int denied = count(fx, Metric.MCP_DENIED);

                Answer answer = peer.call("list_sources", args("page", page));

                assertThat(answer.withoutCorrelation()).isEqualTo(forged.withoutCorrelation());
                assertThat(fx.env.audited).hasSize(events + 1);
                AuditEvent event = fx.env.audited.get(events);
                assertThat(event.policyDecision()).isEqualTo("DENY:LINK_INVALID");
                assertThat(event.fieldDispositions()).containsEntry("link", "REFUSED:LINK_MALFORMED");
                assertThat(count(fx, Metric.MCP_DENIED)).isEqualTo(denied + 1);
            }
            // four invalid pages have counted four failures; a fifth failure is within the limit of five ...
            Answer fifth = peer.call("list_sources", args("page", "AAAAAAAAAAAAAAAAAAAAAAAA"));
            assertThat(fifth.text()).isEqualTo("The link is not valid.");
            // ... and the sixth, of any kind, is over it. Had any invalid page not counted, this is LINK_INVALID.
            Answer sixth = peer.call("list_sources", args("page", 7));
            assertThat(sixth.text()).isEqualTo("LINK_ATTEMPTS_EXHAUSTED");
            assertThat(fx.env.audited.get(fx.env.audited.size() - 1).policyDecision())
                    .isEqualTo("DENY:LINK_ATTEMPTS_EXHAUSTED");
        }
    }

    @Test
    @DisplayName("a violation elsewhere in a call that carries page is also a forged link")
    void anyViolationWithPageIsAForgedLink() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL,
                ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(fx.wiring)) {
            Answer answer = peer.call("list_sources", args("page", "AAAAAAAAAAAAAAAAAAAAAAAA", "pageSize", 0));

            assertThat(answer.text()).isEqualTo("The link is not valid.");
        }
        assertThat(fx.env.audited).singleElement().satisfies(event ->
                assertThat(event.fieldDispositions()).containsEntry("link", "REFUSED:LINK_MALFORMED"));
    }

    @Test
    @DisplayName("no authenticated caller: the same event shape for a valid and an invalid call, never a link refusal")
    void unauthenticatedValidAndInvalidAreAlike() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL, null);

        try (Peer peer = new Peer(fx.wiring)) {
            Answer invalid = peer.call("get_entity_context", args("entityType", 5, "subjectId", "x"));
            Answer valid = peer.call("get_entity_context", args("entityType", "CUSTOMER", "subjectId", "x"));
            Answer listed = peer.call("list_sources", args("page", 7));
            Answer listedValid = peer.call("list_sources", args());

            for (Answer answer : List.of(invalid, valid, listed, listedValid)) {
                assertThat(answer.text()).isEqualTo("NO_AUTHENTICATED_CALLER");
            }
        }
        assertThat(fx.env.audited).hasSize(4);
        for (AuditEvent event : fx.env.audited) {
            assertThat(event.principalId()).isEqualTo("unauthenticated");
            assertThat(event.policyDecision()).isEqualTo("DENY:NO_AUTHENTICATED_CALLER");
            assertThat(event.fieldDispositions()).doesNotContainKey("link");
        }
        assertThat(fx.env.audited.get(0).entityType()).isEqualTo("<unregistered>");
        assertThat(fx.env.audited.get(1).entityType()).isEqualTo("CUSTOMER");
        assertThat(count(fx, Metric.MCP_DENIED)).isEqualTo(4);
    }

    @Test
    @DisplayName("a role without the capability gets TOOL_NOT_PERMITTED for valid and invalid input alike")
    void notPermittedWinsOverInvalidInput() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(
                Set.of("GET_ENTITY_CONTEXT"), ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(fx.wiring)) {
            for (Map<String, Object> arguments : List.of(args(), args("pageSize", 0), args("page", 7))) {
                assertThat(peer.call("list_sources", arguments).text()).isEqualTo("TOOL_NOT_PERMITTED");
            }
            assertThat(peer.call("describe_source", args("source", "x")).text()).isEqualTo("TOOL_NOT_PERMITTED");
            assertThat(peer.call("describe_source", args("source", 5)).text()).isEqualTo("TOOL_NOT_PERMITTED");
        }
        assertThat(fx.env.audited).hasSize(5);
        assertThat(fx.env.audited).allSatisfy(event ->
                assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_NOT_PERMITTED"));
    }

    @Test
    @DisplayName("a client text sentinel in every argument reaches no result, audit record or log line")
    void noClientTextEscapes() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL,
                ListSourcesToolTest.CALLER);
        Map<String, Object> object = Map.of("x", SENTINEL);
        List<String> results = new ArrayList<>();

        try (Peer peer = new Peer(fx.wiring)) {
            for (String tool : List.of("get_entity_context", "compare_entity_sources")) {
                results.add(peer.call(tool, args("entityType", SENTINEL, "subjectId", object,
                        "targetSources", List.of(SENTINEL, SENTINEL), "principalId", SENTINEL,
                        "approvalId", SENTINEL)).result().toString());
            }
            results.add(peer.call("list_sources", args("entityType", SENTINEL + " bad", "keyNamespace", SENTINEL,
                    "pageSize", SENTINEL, "page", SENTINEL + "!", "scopeId", SENTINEL)).result().toString());
            results.add(peer.call("list_sources", args("entityType", SENTINEL, "pageSize", object))
                    .result().toString());
            results.add(peer.call("describe_source", args("source", object, "extra", SENTINEL, "caseId", SENTINEL))
                    .result().toString());
        }

        assertThat(fx.env.audited).hasSize(5);
        assertThat(String.join("\n", results)).doesNotContain(SENTINEL);
        assertThat(fx.env.audited.toString()).doesNotContain(SENTINEL);
        assertThat(loggedText()).doesNotContain(SENTINEL);
        assertThat(loggedText()).doesNotContain("input validation failed");
    }

    @Test
    @DisplayName("two different violations give identical TOOL_INPUT_INVALID results apart from the correlation id")
    void violationsAreNotDistinguishable() {
        EveryToolValidatesInputTest.Fixture fx = new EveryToolValidatesInputTest.Fixture(ALL,
                ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(fx.wiring)) {
            Answer wrongType = peer.call("get_entity_context", args("entityType", 5, "subjectId", "x"));
            Answer missing = peer.call("get_entity_context", args("entityType", "CUSTOMER"));
            Answer blank = peer.call("get_entity_context", args("entityType", " ", "subjectId", "x"));
            Answer emptyList = peer.call("get_entity_context",
                    args("entityType", "CUSTOMER", "subjectId", "x", "targetSources", List.of()));

            assertThat(wrongType.text()).isEqualTo("TOOL_INPUT_INVALID");
            assertThat(missing.withoutCorrelation()).isEqualTo(wrongType.withoutCorrelation());
            assertThat(blank.withoutCorrelation()).isEqualTo(wrongType.withoutCorrelation());
            assertThat(emptyList.withoutCorrelation()).isEqualTo(wrongType.withoutCorrelation());
            assertThat(missing.correlationId()).isNotEqualTo(wrongType.correlationId());
        }
    }
}
