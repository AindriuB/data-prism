package io.github.aindriub.dataprism.audit;

/** Stub: red phase. */
public record AuditRouting(String eventDataset, String dataStreamType, String dataStreamDataset,
                           String dataStreamNamespace) {

    public static AuditRouting none() {
        throw new UnsupportedOperationException();
    }

    public void checkAgainst(AuditFieldMapping mapping) {
        throw new UnsupportedOperationException();
    }
}
