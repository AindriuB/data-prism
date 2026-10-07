package io.github.aindriub.dataprism.audit;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Stub: red phase. */
public final class SegmentedJsonAuditSink implements AuditSink, Closeable {

    public SegmentedJsonAuditSink(Path directory, AuditFieldMapping mapping, AuditRouting routing) {
        throw new UnsupportedOperationException();
    }

    SegmentedJsonAuditSink(Path directory, AuditFieldMapping mapping, AuditRouting routing,
                           SegmentedFileAuditSink.ChannelOpener opener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void record(AuditEvent event) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException();
    }
}
