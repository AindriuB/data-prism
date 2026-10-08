package io.github.aindriub.dataprism.audit.sink;

import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Test-only bridge to the package-private channel constructor, for fault injection from other packages. */
public final class FileAuditSinkTestAccess {

    private FileAuditSinkTestAccess() {
    }

    public static FileAuditSink over(Path path, FileChannel channel) {
        return new FileAuditSink(path, channel);
    }
}
