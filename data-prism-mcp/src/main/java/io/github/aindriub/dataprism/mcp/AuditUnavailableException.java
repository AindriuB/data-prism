package io.github.aindriub.dataprism.mcp;

/**
 * Thrown when {@code audit.record(...)} fails at a call site — {@code deny}
 * or {@code denyUnauthenticated} — that must record before a response is
 * returned. The call still aborts: this exception propagates out of the
 * tool's call handler uncaught, so the MCP SDK never renders a
 * {@code CallToolResult} for the request that triggered it.
 *
 * <p>Carries only the stable code {@link #CODE} in its own message — never
 * the causing exception's message, its class name, or its cause's message.
 *
 * <p>Deliberately never set as {@link Throwable#getCause()}: the MCP SDK
 * that renders this exception for the client (io.modelcontextprotocol.spec.
 * McpError) builds the response it sends over the wire by walking the whole
 * {@code getCause()} chain of whatever escapes the call handler — appending
 * every cause's class name and message, not only the outermost exception's.
 * A {@code getCause()} of, say, {@code FileAuditSink.PoisonedException}
 * would therefore still name the operator's audit file path in the response,
 * however clean this exception's own message is. The original failure is
 * instead kept as a {@link Throwable#addSuppressed(Throwable) suppressed}
 * exception — visible to a full server-side stack trace, invisible to the
 * SDK's cause-chain walk — so the diagnostic detail survives for the
 * server's own logs without crossing the boundary this exception exists to
 * hold.
 */
public final class AuditUnavailableException extends RuntimeException {

    public static final String CODE = "AUDIT_UNAVAILABLE";

    public AuditUnavailableException(Throwable auditFailure) {
        super(CODE);
        if (auditFailure != null) {
            addSuppressed(auditFailure);
        }
    }
}
