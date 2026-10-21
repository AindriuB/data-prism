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

    /**
     * A granted reservation tied to {@code origin}, an opaque token the
     * implementation chooses to identify the live counters that granted it (for
     * example a per-scope state object or a generation number).
     */
    public static ReadReservation granted(String scopeId, String subjectId, int reads, Object origin) {
        if (reads <= 0) {
            throw new IllegalArgumentException("reads must be positive");
        }
        return new ReadReservation(scopeId, subjectId, reads, null, Objects.requireNonNull(origin, "origin"));
    }

    /**
     * An opaque handle owned by the implementation that granted this
     * reservation; {@code null} for a refusal and for one made without an origin.
     * Nothing else should interpret it.
     *
     * <p>Contract: the origin is not proof of issue, because anyone holding a
     * live reservation can read it and build another reservation around it. An
     * implementation MUST recognise its own reservations by its own record (the
     * object's identity, or an unguessable id it stores, such as sealed state),
     * and MUST no-op any release it cannot match, including one for a scope that
     * was forgotten and recreated. A reservation made without an origin (the
     * three-argument {@code granted}) cannot be released by an implementation
     * that relies on its own record.
     */
    public Object origin() {
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
     * Reads this reservation still holds, for the implementation that issued it.
     */
    int remaining() {
        return held.get();
    }

    /**
     * For implementations of {@link ScopeBudget}: takes up to {@code unused}
     * reads out of what this reservation still holds.
     *
     * @return how many reads the implementation must now return to its counters;
     *         zero for a refused reservation or reads already returned. A holder that
     *         empties its own reservation this way makes any later release return
     *         nothing: it fails closed, and those reads stay charged
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
