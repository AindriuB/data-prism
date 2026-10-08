package io.github.aindriub.dataprism.core.correlation;

import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Puts the validated external correlation id into the SLF4J MDC under an
 * operator-configured key, for the duration of a scope.
 *
 * <p>Only {@link ExternalCorrelationId#value()} is ever placed in the MDC: an
 * absent id and a rejected one place nothing, and the rejected text is never
 * held by this class at all. {@link #close()} on a scope restores the key's
 * previous value, or removes the key if there was none, so a pooled or reused
 * thread never carries a finished call's id into the next task.
 *
 * <p>Scopes are always opened on the thread whose log lines should carry the
 * key. Nothing is copied from another thread's MDC and nothing is inherited.
 */
public final class CorrelationMdc {

    /** What a configured key must match. */
    public static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    /**
     * Exact names, compared case-insensitively, that tracing integrations and
     * Spring Boot's ECS structured logging already write.
     */
    public static final List<String> RESERVED_NAMES = List.of(
            "traceId", "spanId", "trace_id", "span_id", "trace_flags", "trace.id", "span.id",
            "transaction.id", "message");

    /** Prefixes, compared case-insensitively, that ECS structured logging writes as top-level names. */
    public static final List<String> RESERVED_PREFIXES = List.of(
            "ecs.", "log.", "process.", "service.", "error.", "event.");

    private static final CorrelationMdc OFF = new CorrelationMdc(null, null);
    private static final Scope NOOP = () -> { };

    private final String key;
    private final MDCAdapter adapter;

    private CorrelationMdc(String key, MDCAdapter adapter) {
        this.key = key;
        this.adapter = adapter;
    }

    /** An instance that never touches the MDC. */
    public static CorrelationMdc off() {
        return OFF;
    }

    /** Writes through {@link MDC#getMDCAdapter()}. */
    public static CorrelationMdc of(String key) {
        return of(key, MDC.getMDCAdapter());
    }

    public static CorrelationMdc of(String key, MDCAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("MDC key is not a valid name");
        }
        return new CorrelationMdc(key, adapter);
    }

    /** True if the key is one of {@link #RESERVED_NAMES} or starts with one of {@link #RESERVED_PREFIXES}. */
    public static boolean isReserved(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return RESERVED_NAMES.stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(lower))
                || RESERVED_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    public boolean enabled() {
        return key != null;
    }

    public Scope open(InboundCorrelation inbound) {
        return open(inbound == null ? Optional.empty() : inbound.id());
    }

    public Scope open(Optional<ExternalCorrelationId> id) {
        if (key == null || id == null || id.isEmpty()) {
            return NOOP;
        }
        String previous = adapter.get(key);
        adapter.put(key, id.get().value());
        return () -> {
            if (previous != null) {
                adapter.put(key, previous);
            } else {
                adapter.remove(key);
            }
        };
    }

    /** Closes without throwing, so it can sit in a {@code finally}. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
