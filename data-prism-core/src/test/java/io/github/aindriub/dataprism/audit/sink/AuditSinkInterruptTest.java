package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
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
    void fileSinkWritesDurablyKeepsTheFlagSetAndStaysUsableWhenInterruptedOnEntry() throws Exception {
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
    void segmentedSinkWritesDurablyKeepsTheFlagSetAndStaysUsableWhenInterruptedOnEntry() throws Exception {
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
    void fileSinkWritesDurablyAndStaysUsableWhenInterruptedDuringTheWrite() throws Exception {
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
    void segmentedSinkWritesDurablyAndStaysUsableWhenInterruptedDuringTheWrite() throws Exception {
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
    void segmentedSinkRollsToTheNewSegmentWithoutPoisoningWhenInterruptedOnEntry() throws Exception {
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
    void fileSinkIsPoisonedWhenTheChannelIsClosedByInterrupt() throws Exception {
        Path file = tempDir.resolve("a.log");
        FileChannel real = open(file);
        FileAuditSink sink = FileAuditSinkTestAccess.over(file, new ThrowingChannel(real));
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(ClosedByInterruptException.class);
        assertPoisoned(sink);
        real.close();
    }

    @Test
    void segmentedSinkIsPoisonedWhenTheChannelIsClosedByInterrupt() {
        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> new ThrowingChannel(open(segment)));
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(ClosedByInterruptException.class);
        assertPoisoned(sink);
    }

    // ---- hand-off failure ----

    @Test
    void fileSinkThrowsAndIsPoisonedWhenTheHandOffFails() throws Exception {
        Path file = tempDir.resolve("a.log");
        FileAuditSink sink = new FileAuditSink(file, open(file), rejecting());
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(RuntimeException.class);
        assertThat(Files.size(file)).isZero();
        assertPoisoned(sink);
        sink.close();
    }

    @Test
    void segmentedSinkThrowsAndIsPoisonedWhenTheHandOffFails() {
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

    // ---- the flag on a failure path ----

    @Test
    void fileSinkLeavesTheFlagSetWhenTheWriteFailsAfterAnInterruptOnEntry() throws Exception {
        Path file = tempDir.resolve("a.log");
        FileChannel real = open(file);
        FileAuditSink sink = FileAuditSinkTestAccess.over(file, new ThrowingChannel(real));
        assertThat(flagAfterFailure(true, () -> sink.record(event("e1")))).isTrue();
        real.close();
    }

    @Test
    void fileSinkLeavesTheFlagClearWhenTheWriteFailsAndNoInterruptWasSet() throws Exception {
        Path file = tempDir.resolve("a.log");
        FileChannel real = open(file);
        FileAuditSink sink = FileAuditSinkTestAccess.over(file, new ThrowingChannel(real));
        assertThat(flagAfterFailure(false, () -> sink.record(event("e1")))).isFalse();
        real.close();
    }

    @Test
    void segmentedSinkLeavesTheFlagSetWhenTheWriteFailsAfterAnInterruptOnEntry() {
        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> new ThrowingChannel(open(segment)));
        assertThat(uncheckedFlagAfterFailure(true, () -> sink.record(event("e1")))).isTrue();
    }

    @Test
    void segmentedSinkLeavesTheFlagClearWhenTheWriteFailsAndNoInterruptWasSet() {
        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> new ThrowingChannel(open(segment)));
        assertThat(uncheckedFlagAfterFailure(false, () -> sink.record(event("e1")))).isFalse();
    }

    @Test
    void fileSinkKeepsTheFlagSetWhenInterruptedWhileParkedInAWriteThatThenFails() throws Exception {
        Path file = tempDir.resolve("a.log");
        ParkingChannel parking = new ParkingChannel(open(file));
        parking.failAfterRelease = true;
        try (FileAuditSink sink = FileAuditSinkTestAccess.over(file, parking)) {
            assertThat(interruptMidFailingWrite(sink, parking)).isTrue();
            assertPoisoned(sink);
        }
    }

    @Test
    void segmentedSinkKeepsTheFlagSetWhenInterruptedWhileParkedInAWriteThatThenFails() throws Exception {
        Path dir = tempDir.resolve("seg");
        ParkingChannel[] parking = new ParkingChannel[1];
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, segment -> {
            parking[0] = new ParkingChannel(open(segment));
            return parking[0];
        })) {
            sink.record(event("e0"));
            parking[0].failAfterRelease = true;
            assertThat(interruptMidFailingWrite(sink, parking[0])).isTrue();
            assertPoisoned(sink);
        }
    }

    @Test
    void fileSinkLeavesTheFlagAsTheCallerHadItWhenTheHandOffFails() throws Exception {
        Path file = tempDir.resolve("a.log");
        try (FileAuditSink sink = new FileAuditSink(file, open(file), rejecting())) {
            assertThat(flagAfterFailure(true, () -> sink.record(event("e1")))).isTrue();
        }
        Path other = tempDir.resolve("b.log");
        try (FileAuditSink sink = new FileAuditSink(other, open(other), rejecting())) {
            assertThat(flagAfterFailure(false, () -> sink.record(event("e1")))).isFalse();
        }
    }

    @Test
    void segmentedSinkLeavesTheFlagAsTheCallerHadItWhenTheHandOffFails() {
        SegmentedFileAuditSink set = new SegmentedFileAuditSink(tempDir.resolve("s1"),
                SegmentedFileAuditSink.APPEND_OPENER, 0, rejecting());
        assertThat(uncheckedFlagAfterFailure(true, () -> set.record(event("e1")))).isTrue();
        SegmentedFileAuditSink clear = new SegmentedFileAuditSink(tempDir.resolve("s2"),
                SegmentedFileAuditSink.APPEND_OPENER, 0, rejecting());
        assertThat(uncheckedFlagAfterFailure(false, () -> clear.record(event("e1")))).isFalse();
    }

    @Test
    void anErrorFromTheHandOffPoisonsBothSinksAndTheRecorderRollsBackItsSequence() throws Exception {
        Path file = tempDir.resolve("a.log");
        try (FileAuditSink sink = new FileAuditSink(file, open(file), failingOnceWith(new OutOfMemoryError(
                "unable to create native thread")))) {
            assertRecorderRollsBackAndSinkIsPoisoned(sink);
        }
        assertThat(Files.readAllLines(file)).hasSize(1);

        Path dir = tempDir.resolve("seg");
        SegmentedFileAuditSink segmented = new SegmentedFileAuditSink(dir, SegmentedFileAuditSink.APPEND_OPENER, 0,
                failingOnceWith(new OutOfMemoryError("unable to create native thread")));
        assertRecorderRollsBackAndSinkIsPoisoned(segmented);
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(1);
        segmented.close();
    }

    /** First record lands, the second hits the hand-off Error, the third is refused: sequences 1, 2, 2. */
    private void assertRecorderRollsBackAndSinkIsPoisoned(AuditSink sink) {
        java.util.List<Long> attempted = new java.util.ArrayList<>();
        AuditSink spy = e -> {
            attempted.add(e.sequence());
            sink.record(e);
        };
        java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"),
                java.time.ZoneOffset.UTC);
        io.github.aindriub.dataprism.audit.AuditRecorder recorder =
                new io.github.aindriub.dataprism.audit.AuditRecorder(spy, clock, "w");
        io.github.aindriub.dataprism.audit.AuditEntry entry = new io.github.aindriub.dataprism.audit.AuditEntry(
                "p", "c", "t", "ty", "s", "f", "pr", "sc", "pu", "ca", "ALLOW", Set.of("crm:ANSWERED"), Set.of(),
                "co", java.util.Map.of(), "", "");
        recorder.record(entry);
        assertThatThrownBy(() -> recorder.record(entry)).isInstanceOf(UninterruptedWrites.HandOffException.class)
                .hasCauseInstanceOf(OutOfMemoryError.class);
        assertThatThrownBy(() -> recorder.record(entry)).isInstanceOf(FileAuditSink.PoisonedException.class);
        assertThat(attempted).containsExactly(1L, 2L, 2L);
    }

    /** The first hand-off runs inline, the second throws {@code error}, later ones run inline again. */
    private static UninterruptedWrites failingOnceWith(Error error) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        return new UninterruptedWrites(task -> {
            if (calls.incrementAndGet() == 2) {
                throw error;
            }
            task.run();
        });
    }

    /** Records on a fresh thread with the flag set or clear; returns the flag after the expected failure. */
    private static boolean flagAfterFailure(boolean setOnEntry, ThrowingRunnable body) throws InterruptedException {
        AtomicBoolean flag = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            if (setOnEntry) {
                Thread.currentThread().interrupt();
            }
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        t.join();
        assertThat(failure.get()).isNotNull();
        return flag.get();
    }

    private static boolean uncheckedFlagAfterFailure(boolean setOnEntry, ThrowingRunnable body) {
        try {
            return flagAfterFailure(setOnEntry, body);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As {@link #interruptMidWrite}, but the parked write then fails; the caller must see the failure and its flag. */
    private static boolean interruptMidFailingWrite(AuditSink sink, ParkingChannel parking) throws Exception {
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
        assertThat(failure.get()).isInstanceOf(UncheckedIOException.class);
        return flag.get();
    }

    // ---- close ----

    @Test
    void fileSinkRecordStillFailsAfterClose() throws Exception {
        FileAuditSink sink = new FileAuditSink(tempDir.resolve("a.log"));
        sink.close();
        assertThatThrownBy(() -> sink.record(event("e1"))).isInstanceOf(UncheckedIOException.class);
        assertPoisoned(sink);
        assertNoNonDaemonWriterThread();
    }

    @Test
    void segmentedSinkReopensTheSegmentOnTheNextRecordAfterClose() throws Exception {
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
        volatile boolean failAfterRelease;

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
                if (failAfterRelease) {
                    throw new IOException("simulated disk failure");
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
