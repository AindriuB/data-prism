package io.github.aindriub.dataprism.audit;

/**
 * Where checkpoints go. Deliberately separate from {@link AuditSink}: a
 * checkpoint only helps if whoever can edit the audit file cannot also edit
 * this destination. An implementation must throw if the checkpoint is not
 * durably recorded.
 */
public interface AuditCheckpointSink {

    void record(AuditCheckpoint checkpoint);
}
