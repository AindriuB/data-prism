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
 *
 * <p>A failed write can be a short write: the channel may have already placed
 * some prefix of the record's bytes wherever it places bytes before the
 * failure that stops the rest. That prefix has no terminating newline, so on
 * its own it is harmless — {@link AuditRecordFormat}'s contract already treats
 * a newline-less trailing chunk as an in-progress write, not a record. But it
 * is only harmless while it stays trailing. If this sink kept using the
 * channel after such a failure, the next successful {@code record()} would
 * append directly after that fragment, and its own {@code force(true)} would
 * make the concatenation — fragment plus complete record, both durable as one
 * newline-terminated line — permanent. That line would then parse as a
 * field-count error in the middle of the file: a false signal of tampering in
 * exactly the place nothing excuses it. So once a write fails for any reason —
 * a short write, or {@code force(true)} failing after every byte reached the
 * channel — this sink poisons itself: every subsequent {@link
 * #record(AuditEvent)} throws immediately without touching the channel again,
 * rather than risking another write landing after whatever the failed write
 * left behind. This class deliberately does not attempt to inspect, truncate
 * or repair the file to recover from that state; whether the failed write's
 * bytes reached the page cache at all is not knowable from here.
 */
public final class FileAuditSink implements AuditSink, Closeable {

    private final Path path;
    private final FileChannel channel;
    private volatile IOException poisonedBy;

    public FileAuditSink(Path path) {
        this.path = path;
        try {
            this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new OpenFailedException("AUDIT_SINK_OPEN_FAILED", path, e);
        }
    }

    /**
     * Visible for testing only: lets a test inject a {@link FileChannel} that
     * fails in ways a real one only fails rarely (a short write, or a
     * {@code force(true)} failure), to prove {@link #record(AuditEvent)}
     * poisons itself afterwards. The public constructor above is the only one
     * production code uses.
     */
    FileAuditSink(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    @Override
    public synchronized void record(AuditEvent event) {
        if (poisonedBy != null) {
            throw new PoisonedException("AUDIT_SINK_POISONED", path, poisonedBy);
        }
        String line = AuditRecordFormat.serialize(event) + "\n";
        ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Thrown by every {@link #record(AuditEvent)} call after an earlier write
     * to this sink failed. The sink does not attempt to recover once that has
     * happened — see the class javadoc for why — so it stays poisoned for the
     * rest of its lifetime; a new event is never named here, matching {@link
     * OpenFailedException}.
     */
    public static final class PoisonedException extends RuntimeException {

        private final String code;
        private final Path path;

        PoisonedException(String code, Path path, IOException cause) {
            super(code + ": audit sink at " + path + " failed a previous write and will not accept more records",
                    cause);
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
