package io.github.aindriub.dataprism.core.limits;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The outcome of {@link ScopeBudget#reserve}: reads held against a subject's
 * counter and the scope total, or a refusal that held nothing.
 *
 * <p>A granted reservation tracks how many of its reads are still held, so a
 * release can never hand back more than was reserved nor hand back the same
 * read twice.
 */
public final class ReadReservation {

    /** Which limit refused a reservation. */
    public enum Refusal {
        /** The subject's own counter would have passed its budget. */
        SUBJECT,
        /** The scope's total would have passed its budget. */
        SCOPE_TOTAL
    }

    private final String scopeId;
    private final String subjectId;
    private final int reads;
    private final Refusal refusedBy;
    private final AtomicInteger held;
    private final Object origin;

    private ReadReservation(String scopeId, String subjectId, int reads, Refusal refusedBy, Object origin) {
        this.origin = origin;
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.reads = reads;
        this.refusedBy = refusedBy;
        this.held = new AtomicInteger(refusedBy == null ? reads : 0);
    }

    public static ReadReservation granted(String scopeId, String subjectId, int reads) {
        if (reads <= 0) {
            throw new IllegalArgumentException("reads must be positive");
        }
        return new ReadReservation(scopeId, subjectId, reads, null, null);
    }

    /** Granted by an implementation that must recognise its own reservations later. */
    static ReadReservation grantedBy(Object origin, String scopeId, String subjectId, int reads) {
        return new ReadReservation(scopeId, subjectId, reads, null, origin);
    }

    Object origin() {
        return origin;
    }

    public static ReadReservation refused(String scopeId, String subjectId, int reads, Refusal refusedBy) {
        return new ReadReservation(scopeId, subjectId, reads, Objects.requireNonNull(refusedBy, "refusedBy"), null);
    }

    public boolean granted() {
        return refusedBy == null;
    }

    /** @return who refused, or {@code null} when the reservation was granted */
    public Refusal refusedBy() {
        return refusedBy;
    }

    public String scopeId() {
        return scopeId;
    }

    public String subjectId() {
        return subjectId;
    }

    /** @return the number of reads asked for */
    public int reads() {
        return reads;
    }

    /**
     * For implementations of {@link ScopeBudget}: takes up to {@code unused}
     * reads out of what this reservation still holds.
     *
     * @return how many reads the implementation must now return to its counters;
     *         zero for a refused reservation or reads already returned
     */
    public int takeUnused(int unused) {
        if (unused < 0) {
            throw new IllegalArgumentException("unused must not be negative");
        }
        while (true) {
            int current = held.get();
            int take = Math.min(current, unused);
            if (take == 0 || held.compareAndSet(current, current - take)) {
                return take;
            }
        }
    }

    @Override
    public String toString() {
        return "ReadReservation[reads=" + reads + ", refusedBy=" + refusedBy + "]";
    }
}
