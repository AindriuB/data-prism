package io.github.aindriub.dataprism.audit;

/**
 * Where checkpoints go. Deliberately separate from {@link AuditSink}: a
 * checkpoint only helps if whoever can edit the audit file cannot also edit
 * this destination. An implementation must throw if the checkpoint is not
 * durably recorded.
 */
public interface AuditCheckpointSink {

    void record(AuditCheckpoint checkpoint);

    /**
     * The {@link AuditCheckpoint.Kind#RETENTION_ANCHOR} checkpoints already recorded here, which
     * {@link AuditRetention} reads so it can tell whether the first segment it is about to purge
     * follows an earlier purge exactly. A sink that cannot read back returns the empty list, and
     * then purge only accepts a chain that starts at GENESIS: it fails closed.
     */
    default java.util.List<AuditCheckpoint> retentionAnchors() {
        return java.util.List.of();
    }
}
