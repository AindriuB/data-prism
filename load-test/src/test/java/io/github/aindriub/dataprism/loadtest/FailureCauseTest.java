package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Each cause comes from its own branch of the classifier, driven by a real exception of that shape. */
class FailureCauseTest {

    private static McpError mcp(int code) {
        return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code, "x"));
    }

    private static RuntimeException wrapped(Throwable cause) {
        return new IllegalStateException("outer", new RuntimeException("middle", cause));
    }

    @Test
    void aConnectTimeoutIsNotAReadTimeout() {
        assertThat(FailureCause.of(wrapped(new HttpConnectTimeoutException("connect timed out"))))
                .isEqualTo(FailureCause.CONNECT_TIMEOUT);
    }

    @Test
    void aRequestTimeoutOrTimeoutExceptionIsAReadTimeout() {
        assertThat(FailureCause.of(wrapped(new HttpTimeoutException("request timed out"))))
                .isEqualTo(FailureCause.READ_TIMEOUT);
        assertThat(FailureCause.of(wrapped(new TimeoutException()))).isEqualTo(FailureCause.READ_TIMEOUT);
    }

    @Test
    void anMcpErrorIsNamedByItsJsonRpcCode() {
        assertThat(FailureCause.of(mcp(-32700))).isEqualTo(FailureCause.MCP_PARSE_ERROR);
        assertThat(FailureCause.of(mcp(-32600))).isEqualTo(FailureCause.MCP_INVALID_REQUEST);
        assertThat(FailureCause.of(mcp(-32601))).isEqualTo(FailureCause.MCP_METHOD_NOT_FOUND);
        assertThat(FailureCause.of(mcp(-32602))).isEqualTo(FailureCause.MCP_INVALID_PARAMS);
        assertThat(FailureCause.of(mcp(-32603))).isEqualTo(FailureCause.MCP_INTERNAL_ERROR);
        assertThat(FailureCause.of(wrapped(mcp(12345)))).isEqualTo(FailureCause.MCP_ERROR_OTHER);
    }

    @Test
    void anHttpStatusIsReadFromTheTransportsMessageAndGroupedByClass() {
        assertThat(FailureCause.of(new McpTransportException("Bad Request. Status code: 400")))
                .isEqualTo(FailureCause.HTTP_STATUS_4XX);
        assertThat(FailureCause.of(new McpTransportException("Server Not Found. Status code: 404")))
                .isEqualTo(FailureCause.HTTP_STATUS_4XX);
        assertThat(FailureCause.of(new McpTransportException("Unrecognized server error, status code: 503")))
                .isEqualTo(FailureCause.HTTP_STATUS_5XX);
        assertThat(FailureCause.of(new McpTransportException("Authorization error in sendMessage with code 401")))
                .isEqualTo(FailureCause.HTTP_STATUS_4XX);
        assertThat(FailureCause.of(new McpTransportException("odd. Status code: 302")))
                .isEqualTo(FailureCause.HTTP_STATUS_OTHER);
    }

    @Test
    void aBodyThatDoesNotParseIsUnparseable() {
        RuntimeException jackson;
        try {
            JsonMapper.builder().build().readTree("{not json");
            throw new AssertionError("expected a parse failure");
        } catch (RuntimeException e) {
            jackson = e;
        }
        assertThat(FailureCause.of(wrapped(jackson))).isEqualTo(FailureCause.UNPARSEABLE_BODY);
        assertThat(FailureCause.of(new McpTransportException("Error parsing JSON-RPC message: x")))
                .isEqualTo(FailureCause.UNPARSEABLE_BODY);
    }

    @Test
    void aDroppedConnectionIsAConnectionFailure() {
        assertThat(FailureCause.of(wrapped(new ConnectException("Connection refused"))))
                .isEqualTo(FailureCause.CONNECTION_FAILED);
        assertThat(FailureCause.of(wrapped(new IOException("reset")))).isEqualTo(FailureCause.CONNECTION_FAILED);
    }

    @Test
    void anythingElseIsOtherAndNeverThrows() {
        assertThat(FailureCause.of(new IllegalStateException("boom"))).isEqualTo(FailureCause.OTHER);
        assertThat(FailureCause.of(null)).isEqualTo(FailureCause.OTHER);
    }

    @Test
    void theCausesRecordedByTheDriverSumToTheFailedCountBecauseEachBranchWasCounted() {
        Map<String, RuntimeException> failures = new LinkedHashMap<>();
        failures.put("CONNECT_TIMEOUT", wrapped(new HttpConnectTimeoutException("c")));
        failures.put("READ_TIMEOUT", wrapped(new HttpTimeoutException("r")));
        failures.put("MCP_INVALID_PARAMS", mcp(-32602));
        failures.put("HTTP_STATUS_5XX", new McpTransportException("status code: 500"));
        failures.put("CONNECTION_FAILED", wrapped(new ConnectException("refused")));
        failures.put("OTHER", new IllegalStateException("x"));
        ResultAggregator aggregator = new ResultAggregator();
        for (RuntimeException thrown : failures.values()) {
            // Two calls per cause, so the count of a cause is not accidentally one.
            for (int i = 0; i < 2; i++) {
                aggregator.record(LoadDriver.measuredCall(subject -> {
                    throw thrown;
                }, () -> 0L, "m-1", 0L, Duration.ofSeconds(60)));
            }
        }
        // A refusal is not a failed call and must not appear in any cause.
        aggregator.record(CallOutcome.refused(1000, "SCOPE_READ_BUDGET"));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.transportFailures()).isEqualTo(12);
        assertThat(summary.failureCauses().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(12);
        failures.keySet().forEach(cause -> assertThat(summary.failureCauses()).containsEntry(cause, 2L));
        assertThat(summary.failureCauses().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(FailureCause.values()).map(Enum::name).toList());
    }
}
