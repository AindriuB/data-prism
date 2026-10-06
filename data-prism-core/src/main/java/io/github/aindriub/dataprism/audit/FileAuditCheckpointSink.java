package io.github.aindriub.dataprism.audit;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends one JSON line per {@link AuditCheckpoint}, fsynced before {@link
 * #record(AuditCheckpoint)} returns. Same discipline as {@link FileAuditSink}:
 * append-only, and poisoned after any failed write so a short write is never
 * followed by a record that would make it permanent mid-file.
 *
 * <p>Refuses to open the audit file's own path. That guards against a
 * misconfiguration only; it says nothing about whether the two files sit
 * behind different access controls, which is what makes a checkpoint worth
 * having.
 */
public final class FileAuditCheckpointSink implements AuditCheckpointSink, Closeable {

    public static final String SAME_AS_AUDIT_FILE = "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE";

    private final Path path;
    private final FileChannel channel;
    private volatile IOException poisonedBy;

    public FileAuditCheckpointSink(Path checkpointPath, Path auditFilePath) {
        this.path = checkpointPath;
        if (isSame(checkpointPath, auditFilePath)) {
            throw new CheckpointSinkException(SAME_AS_AUDIT_FILE,
                    SAME_AS_AUDIT_FILE + ": checkpoint file " + checkpointPath
                            + " is the audit file; they must be separate files", null);
        }
        try {
            this.channel = FileChannel.open(checkpointPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new CheckpointSinkException("AUDIT_CHECKPOINT_OPEN_FAILED",
                    "AUDIT_CHECKPOINT_OPEN_FAILED: could not open checkpoint file at " + checkpointPath, e);
        }
    }

    private static boolean isSame(Path a, Path b) {
        if (a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())) {
            return true;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public synchronized void record(AuditCheckpoint checkpoint) {
        if (poisonedBy != null) {
            throw new CheckpointSinkException("AUDIT_CHECKPOINT_POISONED",
                    "AUDIT_CHECKPOINT_POISONED: checkpoint file " + path + " failed a previous write", poisonedBy);
        }
        ByteBuffer buffer = ByteBuffer.wrap((checkpoint.toJsonLine() + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            poisonedBy = e;
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized java.util.List<AuditCheckpoint> retentionAnchors() {
        java.util.List<AuditCheckpoint> anchors = new java.util.ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                AuditCheckpoint cp = AuditCheckpoint.fromJsonLine(line);
                if (cp.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR) {
                    anchors.add(cp);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CheckpointSinkException("AUDIT_CHECKPOINT_READ_FAILED",
                    "AUDIT_CHECKPOINT_READ_FAILED: could not read checkpoint file " + path, e);
        }
        return anchors;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    /** A checkpoint sink that could not open, or is refusing writes after an earlier failure. */
    public static final class CheckpointSinkException extends RuntimeException {

        private final String code;

        CheckpointSinkException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
