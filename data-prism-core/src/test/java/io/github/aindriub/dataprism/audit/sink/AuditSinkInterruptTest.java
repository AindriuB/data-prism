package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSinkInterruptTest {

    @TempDir
    Path tempDir;

    private static AuditEvent event(String id) {
        return new AuditEvent(id, Instant.parse("2026-03-01T10:00:00Z"), "investigator-1", "client-1",
                "get_entity_context", "CUSTOMER", "pseudo-1", "fp-1", "DEFAULT", "scope-1", "investigation",
                "CASE-1", "ALLOW", Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", "instance-1", 1,
                "root", "h-" + id);
    }

    private static FileChannel open(Path p) throws IOException {
        return FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private static void assertPoisoned(AuditSink sink) {
        assertThatThrownBy(() -> sink.record(event("after")))
                .isInstanceOfSatisfying(FileAuditSink.PoisonedException.class,
                        e -> assertThat(e.code()).isEqualTo("AUDIT_SINK_POISONED"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Runs {@code body} on a fresh thread, and returns whether that thread's flag was set when it finished. */
    private static boolean flagAfter(ThrowingRunnable body) throws InterruptedException {
        AtomicBoolean flag = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        t.join();
        assertThat(failure.get()).isNull();
        return flag.get();
    }

    // ---- flag already set on entry ----

    @Test
    void fileSink_flagSetOnEntry_recordIsDurableFlagStaysSetSinkUsable() throws Exception {
        Path file = tempDir.resolve("a.log");
        try (FileAuditSink sink = new FileAuditSink(file)) {
            boolean flag = flagAfter(() -> {
                Thread.currentThread().interrupt();
                sink.record(event("e1"));
            });
            assertThat(flag).isTrue();
            sink.record(event("e2"));
        }
        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void segmentedSink_flagSetOnEntry_recordIsDurableFlagStaysSetSinkUsable() throws Exception {
        Path dir = tempDir.resolve("seg");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            boolean flag = flagAfter(() -> {
                Thread.currentThread().interrupt();
                sink.record(event("e1"));
            });
            assertThat(flag).isTrue();
            sink.record(event("e2"));
        }
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(2);
    }

    // ---- interrupt lands mid-write ----

    @Test
    void fileSink_interruptDuringWrite_recordIsDurableAndSinkUsable() throws Exception {
        Path file = tempDir.resolve("a.log");
        ParkingChannel parking = new ParkingChannel(open(file));
        try (FileAuditSink sink = FileAuditSinkTestAccess.over(file, parking)) {
            assertThat(interruptMidWrite(sink, parking)).isTrue();
            assertThat(Files.readAllLines(file)).hasSize(1);
            sink.record(event("e2"));
        }
        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void segmentedSink_interruptDuringWrite_recordIsDurableAndSinkUsable() throws Exception {
        Path dir = tempDir.resolve("seg");
        ParkingChannel[] parking = new ParkingChannel[1];
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> {
            parking[0] = new ParkingChannel(open(segment));
            return parking[0];
        })) {
            // The first record opens the channel; park only on the second.
            sink.record(event("e0"));
            assertThat(interruptMidWrite(sink, parking[0])).isTrue();
            assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(2);
            sink.record(event("e2"));
        }
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(3);
    }

    /** Arms the channel, records on a fresh thread, interrupts that thread while the write is parked. */
    private static boolean interruptMidWrite(AuditSink sink, ParkingChannel parking) throws Exception {
        parking.arm();
        AtomicBoolean flag = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                sink.record(event("e1"));
            } catch (Throwable e) {
                failure.set(e);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        parking.inWrite.await();
        caller.interrupt();
        parking.release.countDown();
        caller.join();
        assertThat(failure.get()).isNull();
        return flag.get();
    }

    // ---- rollover ----

    @Test
    void segmentedSink_rolloverWithFlagSet_writesToNewSegmentAndIsNotPoisoned() throws Exception {
        Path dir = tempDir.resolve("seg");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 1)) {
            sink.record(event("e1"));
            boolean flag = flagAfter(() -> {
                Thread.currentThread().interrupt();
                sink.record(event("e2"));
            });
            assertThat(flag).isTrue();
            sink.record(event("e3"));
        }
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(1);
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.0001.log"))).hasSize(1);
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.0002.log"))).hasSize(1);
    }

    // ---- genuine failure still poisons ----

    @Test
    void fileSink_closedByInterruptFromTheChannel_poisonsTheSink() throws Exception {
        Path file = tempDir.resolve("a.log");
        FileChannel real = open(file);
        FileAuditSink sink = FileAuditSinkTestAccess.over(file, new ThrowingChannel(real));
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(ClosedByInterruptException.class);
        assertPoisoned(sink);
        real.close();
    }

    @Test
    void segmentedSink_closedByInterruptFromTheChannel_poisonsTheSink() {
        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> new ThrowingChannel(open(segment)));
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(ClosedByInterruptException.class);
        assertPoisoned(sink);
    }

    // ---- hand-off failure ----

    @Test
    void fileSink_handOffFailure_throwsAndPoisons() throws Exception {
        Path file = tempDir.resolve("a.log");
        FileAuditSink sink = new FileAuditSink(file, open(file), rejecting());
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(RuntimeException.class);
        assertThat(Files.size(file)).isZero();
        assertPoisoned(sink);
        sink.close();
    }

    @Test
    void segmentedSink_handOffFailure_throwsAndPoisons() {
        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, SegmentedFileAuditSink.APPEND_OPENER, 0,
                rejecting());
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(RuntimeException.class);
        assertPoisoned(sink);
    }

    private static UninterruptedWrites rejecting() {
        return new UninterruptedWrites(task -> {
            throw new RejectedExecutionException("simulated");
        });
    }

    // ---- close ----

    @Test
    void fileSink_afterClose_recordStillFailsAsBefore() throws Exception {
        FileAuditSink sink = new FileAuditSink(tempDir.resolve("a.log"));
        sink.close();
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(UncheckedIOException.class);
        assertPoisoned(sink);
        assertNoNonDaemonWriterThread();
    }

    @Test
    void segmentedSink_afterClose_reopensTheSegmentOnTheNextRecord() throws Exception {
        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir);
        sink.record(event("e1"));
        sink.close();
        sink.record(event("e2"));
        sink.close();
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(2);
        assertNoNonDaemonWriterThread();
    }

    private static void assertNoNonDaemonWriterThread() {
        assertThat(Thread.getAllStackTraces().keySet())
                .filteredOn(t -> t.getName().startsWith("audit-sink-writer"))
                .allMatch(Thread::isDaemon);
    }

    // ---- channels ----

    private static class DelegatingChannel extends FileChannel {
        final FileChannel delegate;

        DelegatingChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
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

    /** Once armed, the next write announces itself and parks until released, then writes for real. */
    private static final class ParkingChannel extends DelegatingChannel {
        final CountDownLatch inWrite = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean armed;

        ParkingChannel(FileChannel delegate) {
            super(delegate);
        }

        void arm() {
            armed = true;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (armed) {
                armed = false;
                inWrite.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    // the interrupt reached the thread that is writing: what a real one would see
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.write(src);
        }
    }

    private static final class ThrowingChannel extends DelegatingChannel {
        ThrowingChannel(FileChannel delegate) {
            super(delegate);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            throw new ClosedByInterruptException();
        }
    }
}
