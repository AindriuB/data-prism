package io.github.aindriub.dataprism.core.link;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The only way code outside {@code core.link} opens a token. One call owns the whole sequence, so a caller cannot
 * skip the lock or count wrongly (sealed-links.md section 4):
 * <ol>
 * <li>check the inputs and the binding bounds ({@code MALFORMED}, uncounted, nothing called);</li>
 * <li>check the lock, refusing a locked principal before the token is opened, valid links included;</li>
 * <li>open the token;</li>
 * <li>on a counted refusal, make one atomic {@link LinkAttemptLimiter#recordFailure}.</li>
 * </ol>
 * Any limiter or opener failure, {@code null} answer or thrown {@link RuntimeException} is refused, never passed.
 * An {@link Error} propagates. No limiter or opener message reaches a decision.
 */
public final class LinkAttemptGate {

    /** Why a call was refused by the limiter or by a failure; an audit-only closed set. */
    public enum ExhaustedCause { LOCKED, OVER_LIMIT, CEILING, LIMITER_UNAVAILABLE, OPEN_FAILED }

    /**
     * The gate's answer. The caller answers with {@link Refusal#clientCode()} and audits
     * {@link Refusal#auditValue()}.
     */
    public sealed interface GateDecision permits Proceed, Invalid, Exhausted {
    }

    /** A decision that refuses the call: {@link Invalid} and {@link Exhausted}. {@link Proceed} has neither. */
    public interface Refusal {

        /** The code the client sees. The caller answers with it and never computes a code itself. */
        String clientCode();

        /** The audit value for the {@code link} disposition key; the decision is {@code DENY:} + clientCode. */
        String auditValue();
    }

    /** The link opened and the principal is not locked. The caller still decides target, relation and payload. */
    public record Proceed(LinkOpenResult.Opened opened) implements GateDecision {
        public Proceed {
            Objects.requireNonNull(opened, "opened");
        }

        @Override
        public String toString() {
            return "Proceed[redacted]";
        }
    }

    /** The link was refused for a reason; the client sees {@code LINK_INVALID}. */
    public record Invalid(LinkReason reason) implements GateDecision, Refusal {
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String clientCode() {
            return LINK_INVALID;
        }

        @Override
        public String auditValue() {
            return reason.auditValue();
        }
    }

    /** The call was refused by the limiter or by a failure. */
    public record Exhausted(ExhaustedCause cause) implements GateDecision, Refusal {
        public Exhausted {
            Objects.requireNonNull(cause, "cause");
        }

        @Override
        public String clientCode() {
            return switch (cause) {
                case LOCKED, OVER_LIMIT, CEILING -> LINK_ATTEMPTS_EXHAUSTED;
                case LIMITER_UNAVAILABLE -> LINK_CHECK_UNAVAILABLE;
                case OPEN_FAILED -> LINK_INVALID;
            };
        }

        @Override
        public String auditValue() {
            return "REFUSED:LINK_ATTEMPTS_" + cause.name();
        }
    }

    static final String LINK_INVALID = "LINK_INVALID";
    static final String LINK_ATTEMPTS_EXHAUSTED = "LINK_ATTEMPTS_EXHAUSTED";
    static final String LINK_CHECK_UNAVAILABLE = "LINK_CHECK_UNAVAILABLE";

    private final LinkOpener opener;
    private final LinkAttemptLimiter limiter;

    public LinkAttemptGate(LinkCodec codec, LinkAttemptLimiter limiter) {
        this(Objects.requireNonNull(codec, "codec")::open, limiter);
    }

    LinkAttemptGate(LinkOpener opener, LinkAttemptLimiter limiter) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    /** Opens a token for a binding, accepting the given relations. Never throws for a runtime failure. */
    public GateDecision open(String token, LinkBinding binding, Set<LinkRelation> accepted) {
        if (token == null || token.isEmpty() || accepted == null) {
            return new Invalid(LinkReason.MALFORMED);
        }
        Optional<AttemptSubject> found = AttemptSubject.of(binding);
        if (found.isEmpty()) {
            return new Invalid(LinkReason.MALFORMED);
        }
        AttemptSubject subject = found.get();

        LinkAttemptLimiter.LockState lock;
        try {
            lock = limiter.lockState(subject);
        } catch (RuntimeException failure) {
            return new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE);
        }
        if (lock == LinkAttemptLimiter.LockState.LOCKED) {
            return new Exhausted(ExhaustedCause.LOCKED);
        }
        if (lock != LinkAttemptLimiter.LockState.UNLOCKED) {
            return new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE);
        }

        LinkOpenResult result;
        try {
            result = opener.open(token, binding, accepted);
        } catch (RuntimeException failure) {
            return new Exhausted(ExhaustedCause.OPEN_FAILED);
        }
        if (result instanceof LinkOpenResult.Opened opened) {
            return new Proceed(opened);
        }
        if (!(result instanceof LinkOpenResult.Refused refused)) {
            return new Exhausted(ExhaustedCause.OPEN_FAILED);
        }
        if (!refused.counted()) {
            return new Invalid(refused.reason());
        }

        LinkAttemptLimiter.Admission admission;
        try {
            admission = limiter.recordFailure(subject);
        } catch (RuntimeException failure) {
            return new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE);
        }
        if (admission == null) {
            return new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE);
        }
        return switch (admission) {
            case WITHIN_LIMIT -> new Invalid(refused.reason());
            case OVER_LIMIT -> new Exhausted(ExhaustedCause.OVER_LIMIT);
            case CEILING -> new Exhausted(ExhaustedCause.CEILING);
            case UNAVAILABLE -> new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE);
        };
    }

    @Override
    public String toString() {
        return "LinkAttemptGate[redacted]";
    }
}
