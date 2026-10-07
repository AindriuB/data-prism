package io.github.aindriub.dataprism.audit;

/**
 * Thrown by {@link AuditRecordFormat#parse(String)} when a line's field count does not match the
 * {@code recordVersion} it declares (a 25-field line claiming version 1 or 2, a 24-field line
 * claiming version 3, or an unsupported version). A torn write cannot produce this shape, so it is
 * a tampering signature and is never an interrupted write.
 */
public final class FieldCountMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    FieldCountMismatchException(String message) {
        super(message);
    }
}
