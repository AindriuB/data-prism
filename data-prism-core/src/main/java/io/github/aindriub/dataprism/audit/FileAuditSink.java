package io.github.aindriub.dataprism.audit;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends every event to a single file, one {@link AuditRecordFormat} line per
 * record, fsynced before {@link #record(AuditEvent)} returns.
 *
 * <p>The file is opened append-only and never rotated, truncated or
 * compacted: rotation is an operational concern, not a correctness property,
 * and is out of scope here. Every field written is exactly the field
 * {@link Slf4jAuditSink} already emits — no value read from a source payload
 * reaches this file.
 *
 * <p>A write is durable before this class returns control to the caller: if
 * the underlying channel throws, the exception propagates out of
 * {@link #record(AuditEvent)} rather than being logged and swallowed, so a
 * failure here is a failure the caller (task 63's recorder) must see.
 */
public final class FileAuditSink implements AuditSink, Closeable {

    private final Path path;
    private final FileChannel channel;

    public FileAuditSink(Path path) {
        this.path = path;
        try {
            this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new OpenFailedException("AUDIT_SINK_OPEN_FAILED", path, e);
        }
    }

    @Override
    public synchronized void record(AuditEvent event) {
        String line = AuditRecordFormat.serialize(event) + "\n";
        ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    /**
     * Thrown when the target path cannot be opened for append. Never names the
     * event that could not be written — there wasn't one yet.
     */
    public static final class OpenFailedException extends RuntimeException {

        private final String code;
        private final Path path;

        OpenFailedException(String code, Path path, IOException cause) {
            super(code + ": could not open audit sink file at " + path, cause);
            this.code = code;
            this.path = path;
        }

        public String code() {
            return code;
        }

        public Path path() {
            return path;
        }
    }
}
