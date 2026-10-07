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

    /**
     * Strict default: a canonical UUID, 16-128 hex characters containing at
     * least one a-f letter, or a W3C traceparent.
     *
     * <p>The letter rule refuses an all-digit string, which is the form of a
     * card number or a long account or national-id number. The UUID branch is
     * unaffected, so an all-digit UUID is still accepted: its fixed 8-4-4-4-12
     * shape is not a typical PAN or account form. This is a syntactic
     * constraint; it does not prove the value is free of personal data.
     */
    public static final String DEFAULT_OPAQUE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{16,128}"
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
