package io.github.aindriub.dataprism.audit;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The JSON projection of the audit log: appends each event, rendered by {@link AuditJsonRenderer},
 * to {@code directory/audit-YYYY-MM-DD.ndjson}, the date being the UTC date of the event.
 *
 * <p>This is a projection, not the record. The hash-chained {@code .log} segments written by
 * {@link SegmentedFileAuditSink} stay authoritative and verifiable; nothing here is chained or
 * verified. Fsync, torn-write poisoning and the day-segment rules are
 * {@link SegmentedFileAuditSink}'s: once a write fails, every later record throws.
 */
public final class SegmentedJsonAuditSink implements AuditSink, Closeable {

    private final SegmentedFileAuditSink delegate;

    /**
     * @throws IllegalArgumentException {@code AUDIT_FIELD_MAPPING_CONFLICT} if a routing path
     *                                  collides with a mapped path
     */
    public SegmentedJsonAuditSink(Path directory, AuditFieldMapping mapping, AuditRouting routing) {
        this(directory, mapping, routing, SegmentedFileAuditSink.APPEND_OPENER);
    }

    SegmentedJsonAuditSink(Path directory, AuditFieldMapping mapping, AuditRouting routing,
                           SegmentedFileAuditSink.ChannelOpener opener) {
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(routing, "routing");
        routing.checkAgainst(mapping);
        this.delegate = new SegmentedFileAuditSink(directory, opener, ".ndjson",
                event -> AuditJsonRenderer.render(event, mapping, routing));
    }

    @Override
    public void record(AuditEvent event) {
        delegate.record(event);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
