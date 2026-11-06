package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;

import java.util.Objects;
import java.util.Optional;

/**
 * What a {@code follow} caller asked for: one sealed link, and nothing else. Scope, principal, purpose and case
 * come from the session, so a caller cannot widen its own access by asking differently.
 *
 * @param link                  the sealed token exactly as the client sent it; untrusted, and never audited or
 *                              logged. An empty string is a refusal, not a link
 * @param toolName              the name the call is audited under
 * @param approvalId            the four-eyes approval this call runs under, or {@code ""}
 * @param approverId            the second principal who approved it, or {@code ""}
 * @param externalCorrelationId the caller's own correlation id, read from the transport context; never null
 */
public record FollowRequest(String link, String toolName, String approvalId, String approverId,
                            Optional<ExternalCorrelationId> externalCorrelationId) {

    /** The tool every {@link #of} request is audited under. */
    public static final String DEFAULT_TOOL_NAME = "follow";

    public FollowRequest {
        Objects.requireNonNull(toolName, "toolName");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        approvalId = approvalId == null ? "" : approvalId;
        approverId = approverId == null ? "" : approverId;
        externalCorrelationId = externalCorrelationId == null ? Optional.empty() : externalCorrelationId;
    }

    /** A request for {@code link} with no approval and no external correlation id. */
    public static FollowRequest of(String link) {
        return new FollowRequest(link, DEFAULT_TOOL_NAME, "", "", Optional.empty());
    }

    /** Shows no token. */
    @Override
    public String toString() {
        return "FollowRequest[redacted]";
    }
}
