package io.github.aindriub.dataprism.security;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** Which tools need a human approval, the per-caller request limit, and how long an approval request lives. */
public record OversightPolicy(
        Set<String> approvalRequiredTools,
        OptionalInt callerRequestLimit,
        Duration callerWindow,
        Duration approvalTtl) {

    public OversightPolicy {
        Objects.requireNonNull(approvalRequiredTools, "approvalRequiredTools");
        Objects.requireNonNull(callerRequestLimit, "callerRequestLimit");
        Objects.requireNonNull(callerWindow, "callerWindow");
        Objects.requireNonNull(approvalTtl, "approvalTtl");
        approvalRequiredTools = Set.copyOf(approvalRequiredTools);
        if (callerRequestLimit.isPresent() && callerRequestLimit.getAsInt() <= 0) {
            throw new IllegalArgumentException("callerRequestLimit must be positive");
        }
        if (callerWindow.isZero() || callerWindow.isNegative()) {
            throw new IllegalArgumentException("callerWindow must be positive");
        }
        if (approvalTtl.isZero() || approvalTtl.isNegative()) {
            throw new IllegalArgumentException("approvalTtl must be positive");
        }
    }

    /** No approval-required tools and no per-caller limit. */
    public static OversightPolicy none() {
        return new OversightPolicy(Set.of(), OptionalInt.empty(), Duration.ofMinutes(1), Duration.ofHours(1));
    }
}
