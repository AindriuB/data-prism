package io.github.aindriub.dataprism.audit;

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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileAuditSinkTest {

    @TempDir
    Path tempDir;

    private static AuditEvent event(String eventId, long sequence, String previousHash, String eventHash) {
        return new AuditEvent(
                eventId,
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "CUSTOMER",
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of("customer-api:ANSWERED"),
                Set.of(),
                "corr-1",
                "instance-1",
                sequence,
                previousHash,
                eventHash);
    }

    @Test
    void opensAppendOnlyAndCreatesFileIfAbsent() throws IOException {
        Path path = tempDir.resolve("audit.log");
        assertThat(Files.exists(path)).isFalse();

        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(event("event-1", 0, "root", "hash-1"));
        }

        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void twoSinkInstancesOverTheSamePathAppendInOrder() throws IOException {
        Path path = tempDir.resolve("audit.log");

        try (FileAuditSink first = new FileAuditSink(path)) {
            first.record(event("event-1", 0, "root", "hash-1"));
            first.record(event("event-2", 1, "hash-1", "hash-2"));
        }

        try (FileAuditSink second = new FileAuditSink(path)) {
            second.record(event("event-3", 2, "hash-2", "hash-3"));
            second.record(event("event-4", 3, "hash-3", "hash-4"));
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(4);
        assertThat(lines.stream().map(AuditRecordFormat::parse).map(AuditEvent::eventId))
                .containsExactly("event-1", "event-2", "event-3", "event-4");
    }

    @Test
    void recordIsDurableWithoutTheTestFlushingOrClosing() throws IOException {
        Path path = tempDir.resolve("audit.log");
        FileAuditSink sink = new FileAuditSink(path);

        sink.record(event("event-1", 0, "root", "hash-1"));

        try (FileChannel reader = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) reader.size());
            while (buffer.hasRemaining() && reader.read(buffer) != -1) {
                // drain
            }
            String content = new String(buffer.array(), StandardCharsets.UTF_8);
            assertThat(content).contains("event-1");
        }

        sink.close();
    }

    @Test
    void constructingOnAnUnopenablePathThrowsWithAStableCodeNamingThePath() {
        Path unopenable = tempDir.resolve("missing-parent-dir").resolve("audit.log");

        assertThatThrownBy(() -> new FileAuditSink(unopenable))
                .isInstanceOf(FileAuditSink.OpenFailedException.class)
                .satisfies(e -> {
                    FileAuditSink.OpenFailedException open = (FileAuditSink.OpenFailedException) e;
                    assertThat(open.code()).isEqualTo("AUDIT_SINK_OPEN_FAILED");
                    assertThat(open.path()).isEqualTo(unopenable);
                    assertThat(open.getMessage()).contains(unopenable.toString());
                });
    }

    @Test
    void anIoFailureDuringRecordPropagatesRatherThanBeingSwallowed() throws IOException {
        Path path = tempDir.resolve("audit.log");
        FileAuditSink sink = new FileAuditSink(path);
        sink.close();

        assertThatThrownBy(() -> sink.record(event("event-1", 0, "root", "hash-1")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void aShortWriteThatThrowsPoisonsTheSinkSoNoFragmentEverGetsAConcatenatedRecordAfterIt() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileChannel real = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
                ShortWriteThenFailChannel faulty = new ShortWriteThenFailChannel(real, 5)) {
            FileAuditSink sink = new FileAuditSink(path, faulty);

            assertThatThrownBy(() -> sink.record(event("event-1", 0, "root", "hash-1")))
                    .isInstanceOf(UncheckedIOException.class);

            byte[] afterFailure = Files.readAllBytes(path);
            assertThat(afterFailure).hasSize(5);
            assertThat(new String(afterFailure, StandardCharsets.UTF_8)).doesNotContain("\n");

            assertThatThrownBy(() -> sink.record(event("event-2", 1, "hash-1", "hash-2")))
                    .isInstanceOf(FileAuditSink.PoisonedException.class)
                    .satisfies(e -> {
                        FileAuditSink.PoisonedException poisoned = (FileAuditSink.PoisonedException) e;
                        assertThat(poisoned.code()).isEqualTo("AUDIT_SINK_POISONED");
                        assertThat(poisoned.path()).isEqualTo(path);
                    });

            byte[] afterSecondAttempt = Files.readAllBytes(path);
            assertThat(afterSecondAttempt).isEqualTo(afterFailure);
        }
    }

    @Test
    void aForceFailureAfterACompleteWriteAlsoPoisonsTheSink() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileChannel real = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
                FailOnForceChannel faulty = new FailOnForceChannel(real)) {
            FileAuditSink sink = new FileAuditSink(path, faulty);

            assertThatThrownBy(() -> sink.record(event("event-1", 0, "root", "hash-1")))
                    .isInstanceOf(UncheckedIOException.class);

            assertThatThrownBy(() -> sink.record(event("event-2", 1, "hash-1", "hash-2")))
                    .isInstanceOf(FileAuditSink.PoisonedException.class);
        }
    }

    /**
     * Delegates to a real channel, but on its first {@code write} call writes
     * only {@code bytesBeforeFailure} bytes and then throws, modelling a disk
     * that fills (or a channel that otherwise fails) partway through a write
     * without closing itself the way an interrupted channel would.
     */
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

    /** Delegates every write, but always fails {@code force(true)}. */
    private static final class FailOnForceChannel extends FileChannel {

        private final FileChannel delegate;

        FailOnForceChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public void force(boolean metaData) throws IOException {
            throw new IOException("simulated force failure");
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
