package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentedFileAuditSinkTest {

    @TempDir
    Path tempDir;

    private static AuditEvent event(String eventId, String timestamp, long sequence, String previousHash,
                                    String eventHash) {
        return new AuditEvent(eventId, Instant.parse(timestamp), "investigator-1", "client-1",
                "get_entity_context", "CUSTOMER", "pseudo-1", "fp-1", "DEFAULT", "scope-1", "investigation",
                "CASE-1", "ALLOW", Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", "instance-1", sequence,
                previousHash, eventHash);
    }

    @Test
    void writesEachEventToTheFileOfItsUtcDate() throws IOException {
        Path dir = tempDir.resolve("segments");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            sink.record(event("e1", "2026-03-01T23:59:59Z", 1, "root", "h1"));
            sink.record(event("e2", "2026-03-02T00:00:00Z", 2, "h1", "h2"));
            sink.record(event("e3", "2026-03-02T10:00:00+05:00", 3, "h2", "h3"));
        }

        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(1);
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-02.log"))).hasSize(2);
        assertThat(dir.toFile().list()).containsExactlyInAnyOrder("audit-2026-03-01.log",
                "audit-2026-03-02.log");
    }

    @Test
    void aShortWriteThatThrowsPoisonsTheWholeSinkIncludingOtherDays() throws IOException {
        Path dir = tempDir.resolve("segments");
        Path day1 = dir.resolve("audit-2026-03-01.log");
        Files.createDirectories(dir);
        FileChannel real = FileChannel.open(day1, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        ShortWriteThenFailChannel faulty = new ShortWriteThenFailChannel(real, 5);
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> faulty);

        assertThatThrownBy(() -> sink.record(event("e1", "2026-03-01T00:00:00Z", 1, "root", "h1")))
                .isInstanceOf(UncheckedIOException.class);
        byte[] afterFailure = Files.readAllBytes(day1);
        assertThat(afterFailure).hasSize(5);
        assertThat(new String(afterFailure, StandardCharsets.UTF_8)).doesNotContain("\n");

        assertThatThrownBy(() -> sink.record(event("e2", "2026-03-01T00:00:01Z", 2, "h1", "h2")))
                .isInstanceOf(FileAuditSink.PoisonedException.class);
        assertThatThrownBy(() -> sink.record(event("e3", "2026-03-02T00:00:00Z", 3, "h2", "h3")))
                .isInstanceOf(FileAuditSink.PoisonedException.class);
        assertThat(Files.exists(dir.resolve("audit-2026-03-02.log"))).isFalse();
        assertThat(Files.readAllBytes(day1)).hasSize(5);
        faulty.close();
    }

    @Test
    void recordIsDurableWithoutTheTestClosing() throws IOException {
        Path dir = tempDir.resolve("segments");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir);
        sink.record(event("event-1", "2026-03-01T00:00:00Z", 1, "root", "h1"));

        assertThat(Files.readString(dir.resolve("audit-2026-03-01.log"))).contains("event-1");
        sink.close();
    }

    private static final class ShortWriteThenFailChannel extends FileChannel {

        private final FileChannel delegate;
        private final int bytesBeforeFailure;
        private final AtomicInteger writeCalls = new AtomicInteger();

        ShortWriteThenFailChannel(FileChannel delegate, int bytesBeforeFailure) {
            this.delegate = delegate;
            this.bytesBeforeFailure = bytesBeforeFailure;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (writeCalls.getAndIncrement() == 0) {
                ByteBuffer limited = src.duplicate();
                limited.limit(Math.min(src.limit(), src.position() + bytesBeforeFailure));
                int written = delegate.write(limited);
                src.position(src.position() + written);
                throw new IOException("simulated short write failure");
            }
            throw new IOException("sink should be poisoned before writing again");
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }

        @Override
        public int read(ByteBuffer dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel position(long newPosition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(ByteBuffer dst, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }
    }
}
