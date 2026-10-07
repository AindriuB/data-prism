package io.github.aindriub.dataprism.core.correlation;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a caller-supplied correlation id. Fails closed: anything that does
 * not validate is empty.
 *
 * <p>A fixed ceiling applies before any configured pattern: at most 256
 * characters, each in {@code [A-Za-z0-9._:/+=-]}.
 */
public final class CorrelationIdPolicy {

    /** Strict default: a UUID, 16-128 hex characters, or a W3C traceparent. */
    public static final String DEFAULT_OPAQUE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|[0-9a-fA-F]{16,128}"
                    + "|00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    private static final int MAX_LENGTH = 256;
    private static final Pattern CEILING = Pattern.compile("[A-Za-z0-9._:/+=-]{1,256}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    private final Pattern opaque; // null in traceparent mode

    private CorrelationIdPolicy(Pattern opaque) {
        this.opaque = opaque;
    }

    public static CorrelationIdPolicy opaque(String regex) {
        try {
            return new CorrelationIdPolicy(Pattern.compile(regex));
        } catch (PatternSyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("INVALID_CORRELATION_PATTERN");
        }
    }

    public static CorrelationIdPolicy traceparent() {
        return new CorrelationIdPolicy(null);
    }

    public Optional<ExternalCorrelationId> validate(String candidate) {
        if (candidate == null || candidate.length() > MAX_LENGTH || !CEILING.matcher(candidate).matches()) {
            return Optional.empty();
        }
        if (opaque != null) {
            return opaque.matcher(candidate).matches()
                    ? Optional.of(new ExternalCorrelationId(candidate, null))
                    : Optional.empty();
        }
        var m = TRACEPARENT.matcher(candidate);
        if (!m.matches() || isZero(m.group(1)) || isZero(m.group(2))) {
            return Optional.empty();
        }
        return Optional.of(new ExternalCorrelationId(m.group(1), candidate));
    }

    private static boolean isZero(String hex) {
        return hex.chars().allMatch(c -> c == '0');
    }
}
