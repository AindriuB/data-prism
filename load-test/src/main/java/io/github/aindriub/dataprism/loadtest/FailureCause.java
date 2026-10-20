package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpError;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a call failed (never reached a tool result). A closed set: every failed call carries exactly one, and the
 * README lists them. No exception message is ever recorded; the only thing read from one is a three-digit HTTP
 * status or a JSON-RPC error code, both of which become a name from this set.
 *
 * <p>{@link #of} tries one rule at a time, in the order below, over the whole cause chain; the first rule that
 * matches names the cause.
 */
enum FailureCause {

    /** The TCP or TLS connection was not established within the connect timeout. */
    CONNECT_TIMEOUT,
    /** The server accepted the connection but did not answer within the request timeout. */
    READ_TIMEOUT,
    /** JSON-RPC {@code -32700}. */
    MCP_PARSE_ERROR,
    /** JSON-RPC {@code -32600}. */
    MCP_INVALID_REQUEST,
    /** JSON-RPC {@code -32601}. */
    MCP_METHOD_NOT_FOUND,
    /** JSON-RPC {@code -32602}. */
    MCP_INVALID_PARAMS,
    /** JSON-RPC {@code -32603}. */
    MCP_INTERNAL_ERROR,
    /** Any other JSON-RPC error code. */
    MCP_ERROR_OTHER,
    /** An HTTP status in 400 to 499. */
    HTTP_STATUS_4XX,
    /** An HTTP status in 500 to 599. */
    HTTP_STATUS_5XX,
    /** Any other HTTP status. */
    HTTP_STATUS_OTHER,
    /** The response body could not be parsed, or held no {@code sources} object. */
    UNPARSEABLE_BODY,
    /** Connection refused, reset or closed. */
    CONNECTION_FAILED,
    /** Anything that matched no rule above. */
    OTHER;

    private static final Pattern STATUS = Pattern.compile("(?i)(?:status code|with code)\\W{0,3}(\\d{3})");
    private static final Pattern BODY = Pattern.compile("(?i)error (?:deserializing|parsing) JSON-RPC message");

    private record Rule(Function<Throwable, FailureCause> match) { }

    private static final List<Rule> RULES = List.of(
            new Rule(t -> t instanceof HttpConnectTimeoutException ? CONNECT_TIMEOUT : null),
            new Rule(t -> t instanceof HttpTimeoutException || t instanceof TimeoutException ? READ_TIMEOUT : null),
            new Rule(t -> t instanceof McpError error && error.getJsonRpcError() != null
                    && error.getJsonRpcError().code() != null ? mcp(error.getJsonRpcError().code()) : null),
            new Rule(FailureCause::httpStatus),
            new Rule(t -> t instanceof JacksonException || (t.getMessage() != null && BODY.matcher(t.getMessage()).find())
                    ? UNPARSEABLE_BODY : null),
            new Rule(t -> t instanceof IOException ? CONNECTION_FAILED : null));

    /** The cause of {@code failure}; {@link #OTHER} when nothing matches. Never throws. */
    static FailureCause of(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && chain.size() < 32 && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        for (Rule rule : RULES) {
            for (Throwable t : chain) {
                FailureCause cause = rule.match().apply(t);
                if (cause != null) {
                    return cause;
                }
            }
        }
        return OTHER;
    }

    boolean isTimeout() {
        return this == CONNECT_TIMEOUT || this == READ_TIMEOUT;
    }

    private static FailureCause mcp(int code) {
        return switch (code) {
            case -32700 -> MCP_PARSE_ERROR;
            case -32600 -> MCP_INVALID_REQUEST;
            case -32601 -> MCP_METHOD_NOT_FOUND;
            case -32602 -> MCP_INVALID_PARAMS;
            case -32603 -> MCP_INTERNAL_ERROR;
            default -> MCP_ERROR_OTHER;
        };
    }

    private static FailureCause httpStatus(Throwable t) {
        if (t.getMessage() == null) {
            return null;
        }
        Matcher matcher = STATUS.matcher(t.getMessage());
        if (!matcher.find()) {
            return null;
        }
        int status = Integer.parseInt(matcher.group(1));
        return status >= 400 && status < 500 ? HTTP_STATUS_4XX : status >= 500 && status < 600 ? HTTP_STATUS_5XX
                : HTTP_STATUS_OTHER;
    }
}
