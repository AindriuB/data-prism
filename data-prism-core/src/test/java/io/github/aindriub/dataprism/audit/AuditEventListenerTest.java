package io.github.aindriub.dataprism.audit;

import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 163. Every wait is a latch, a future with a deadlock bound, or {@code close()}, which drains and
 * joins the dispatcher thread; none is a sleep.
 */
class AuditEventListenerTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final int BOUND_SECONDS = 20;

    /** No SLF4J provider is on this module's test classpath, so a proxy stands in for the appender. */
    private static class Lines {
        final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

        void onWarn(String line) {
            warnings.add(line);
        }

        Logger logger() {
            return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
                    (proxy, method, a) -> {
                        if (method.getName().equals("warn") && a.length == 1) {
                            onWarn((String) a[0]);
                        }
                        return null;
                    });
        }
    }

    private static AuditEntry entry() {
        return new AuditEntry("p", "c", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp", "DEFAULT", "CASE-1",
                "investigation", "CASE-1", "ALLOW", Set.of("customer-api:ANSWERED"), Set.of("x"), "corr",
                java.util.Map.of("customer-api:/name", "REDACT"), "", "");
    }

    private static AuditEventListeners dispatcher(Lines lines, int capacity, AuditEventListener... listeners) {
        return new AuditEventListeners(List.of(listeners), capacity, Duration.ofSeconds(BOUND_SECONDS),
                Duration.ofHours(1), lines.logger());
    }

    private static AuditRecorder recorder(AuditSink sink, AuditEventListeners listeners) {
        return new AuditRecorder(sink, FIXED, "w", null, listeners);
    }

    private static long dispatcherThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> (t.getName().equals("data-prism-audit-listeners")
                        || t.getName().equals(AuditEventListeners.REPORTER_THREAD)) && t.isAlive()).count();
    }

    @Test
    void the_listener_receives_the_event_the_file_holds_after_the_write(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.log");
        List<AuditEvent> seen = Collections.synchronizedList(new ArrayList<>());
        List<String> fileAtDelivery = Collections.synchronizedList(new ArrayList<>());
        AuditEvent returned;
        try (FileAuditSink sink = new FileAuditSink(file);
             AuditEventListeners listeners = dispatcher(new Lines(), 16, event -> {
                 seen.add(event);
                 try {
                     fileAtDelivery.add(Files.readString(file));
                 } catch (java.io.IOException e) {
                     throw new java.io.UncheckedIOException(e);
                 }
             })) {
            AuditRecorder recorder = recorder(sink, listeners);
            returned = recorder.record(entry());
            listeners.close();
        }
        assertThat(seen).containsExactly(returned);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains(returned.eventHash());
        assertThat(fileAtDelivery.get(0)).contains(returned.eventHash());
        assertThat(AuditChainVerifier.verify(file).hasBreak()).isFalse();
    }

    @Test
    void a_failing_sink_never_reaches_the_listener_and_the_sequence_is_rolled_back() {
        List<AuditEvent> seen = Collections.synchronizedList(new ArrayList<>());
        boolean[] fail = {true};
        List<AuditEvent> written = new ArrayList<>();
        AuditSink sink = event -> {
            if (fail[0]) {
                throw new IllegalStateException("disk full");
            }
            written.add(event);
        };
        try (AuditEventListeners listeners = dispatcher(new Lines(), 16, seen::add)) {
            AuditRecorder recorder = recorder(sink, listeners);
            assertThatThrownBy(() -> recorder.record(entry())).isInstanceOf(IllegalStateException.class);
            fail[0] = false;
            AuditEvent next = recorder.record(entry());
            assertThat(next.sequence()).isEqualTo(1);
            listeners.close();
            assertThat(seen).containsExactly(next);
            assertThat(written).containsExactly(next);
        }
    }

    @Test
    void a_throwing_listener_is_isolated_logged_by_class_only_and_others_still_run(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("audit.log");
        Lines lines = new Lines();
        List<AuditEvent> later = Collections.synchronizedList(new ArrayList<>());
        String secret = "https://user:hunter2@sink.example/hook";
        AuditEventListener runtime = e -> {
            throw new IllegalStateException(secret);
        };
        AuditEventListener error = e -> {
            throw new AssertionError(secret);
        };
        List<AuditEvent> returned = new ArrayList<>();
        try (FileAuditSink sink = new FileAuditSink(file);
             AuditEventListeners listeners = dispatcher(lines, 16, runtime, error, later::add)) {
            AuditRecorder recorder = recorder(sink, listeners);
            returned.add(recorder.record(entry()));
            returned.add(recorder.record(entry()));
            listeners.close();
        }
        assertThat(returned.get(1).sequence()).isEqualTo(returned.get(0).sequence() + 1);
        assertThat(later).containsExactlyElementsOf(returned);
        assertThat(AuditChainVerifier.verify(file).hasBreak()).isFalse();
        // two listeners x two events = four failure lines, one per failure
        assertThat(lines.warnings).hasSize(4).allSatisfy(l -> {
            assertThat(l).startsWith("AUDIT_LISTENER_FAILED");
            assertThat(l).doesNotContain("hunter2").doesNotContain(secret);
        });
        assertThat(lines.warnings.get(0)).contains("exception=java.lang.IllegalStateException")
                .contains("eventId=" + returned.get(0).eventId()).contains("sequence=1");
        assertThat(lines.warnings.get(1)).contains("exception=java.lang.AssertionError");
    }

    @Test
    void a_blocked_listener_never_delays_recording_and_overflow_is_dropped_for_listeners_only(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("audit.log");
        Lines lines = new Lines();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        int capacity = 4;
        int total = 50;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (FileAuditSink sink = new FileAuditSink(file);
             AuditEventListeners listeners = dispatcher(lines, capacity, e -> {
                 started.countDown();
                 try {
                     release.await();
                 } catch (InterruptedException ex) {
                     Thread.currentThread().interrupt();
                 }
                 sequences.add(e.sequence());
             })) {
            AuditRecorder recorder = recorder(sink, listeners);
            // The listener cannot return until release is counted down below, so these records
            // finishing at all proves recording does not wait for it.
            recorder.record(entry());
            awaitStarted(started); // the dispatcher holds event 1, so the drop counts below are exact
            Future<?> all = pool.submit(() -> {
                for (int i = 1; i < total; i++) {
                    recorder.record(entry());
                }
            });
            all.get(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(listeners.droppedCount()).isEqualTo(total - 1 - capacity);
            // nothing is logged on the recording thread
            assertThat(lines.warnings).isEmpty();
            release.countDown();
            listeners.close();
            // the flood produced one line, with counts and no event content
            assertThat(lines.warnings).hasSize(1);
            assertThat(lines.warnings.get(0)).startsWith("AUDIT_LISTENER_DROPPED")
                    .doesNotContain("SUBJ-1").doesNotContain("CASE-1").doesNotContain("customer-api");
        } finally {
            pool.shutdownNow();
        }
        // delivered: the one in flight plus the queue's worth, in sequence order
        assertThat(sequences).hasSize(1 + capacity).isSorted();
        assertThat(sequences.get(0)).isEqualTo(1);
        // the log kept all of them, chain intact
        assertThat(Files.readAllLines(file)).hasSize(total);
        assertThat(AuditChainVerifier.verify(file).hasBreak()).isFalse();
    }

    @Test
    void a_single_blocked_record_returns_well_inside_the_latency_budget() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (AuditEventListeners listeners = dispatcher(new Lines(), 8, e -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        })) {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            long start = System.nanoTime();
            recorder.record(entry());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
            release.countDown();
        }
    }

    @Test
    void many_threads_recording_are_unaffected_by_a_hung_listener() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        int threads = 8;
        int perThread = 500;
        List<AuditEvent> written = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (AuditEventListeners listeners = dispatcher(new Lines(), 16, e -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        })) {
            AuditRecorder recorder = recorder(written::add, listeners);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        recorder.record(entry());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(BOUND_SECONDS, TimeUnit.SECONDS);
            }
            assertThat(written).hasSize(threads * perThread);
            assertThat(listeners.droppedCount()).isGreaterThan(0);
            release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_listener_cannot_mutate_the_event_and_the_logged_line_is_unchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.log");
        List<Class<?>> thrown = Collections.synchronizedList(new ArrayList<>());
        Lines lines = new Lines();
        AuditEventListener mutator = e -> {
            Runnable[] attempts = {
                () -> e.sourceSystems().add("evil"),
                () -> e.rejectedArguments().clear(),
                () -> e.fieldDispositions().put("a:/b", "PASS_THROUGH"),
                () -> e.fieldDispositions().clear()};
            for (Runnable attempt : attempts) {
                try {
                    attempt.run();
                } catch (UnsupportedOperationException ex) {
                    thrown.add(ex.getClass());
                }
            }
        };
        AuditEvent returned;
        try (FileAuditSink sink = new FileAuditSink(file);
             AuditEventListeners listeners = dispatcher(lines, 16, mutator)) {
            returned = recorder(sink, listeners).record(entry());
            listeners.close();
        }
        assertThat(thrown).hasSize(4);
        assertThat(lines.warnings).isEmpty();
        assertThat(Files.readAllLines(file)).hasSize(1);
        assertThat(Files.readAllLines(file).get(0)).contains(returned.eventHash());
        assertThat(returned.sourceSystems()).containsExactly("customer-api:ANSWERED");
        assertThat(AuditChainVerifier.verify(file).hasBreak()).isFalse();
    }

    @Test
    void each_listener_sees_events_in_sequence_order() {
        List<Long> first = Collections.synchronizedList(new ArrayList<>());
        List<Long> second = Collections.synchronizedList(new ArrayList<>());
        try (AuditEventListeners listeners = dispatcher(new Lines(), 1000, e -> first.add(e.sequence()),
                e -> second.add(e.sequence()))) {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            for (int i = 0; i < 500; i++) {
                recorder.record(entry());
            }
            listeners.close();
        }
        assertThat(first).hasSize(500).isSorted();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void a_hung_listener_does_not_hide_drops_and_each_tick_reports_counts_only() throws Exception {
        List<String> logged = new ArrayList<>();
        Lines lines = new Lines() {
            @Override void onWarn(String line) {
                logged.add(line);
            }
        };
        Semaphore gate = new Semaphore(0);
        BlockingQueue<Long> entered = new LinkedBlockingQueue<>();
        // a one-hour interval: the scheduler never ticks by itself, the test ticks by hand
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            entered.add(e.sequence());
            gate.acquireUninterruptibly();
        }), 1, Duration.ofSeconds(BOUND_SECONDS), Duration.ofHours(1), lines.logger());
        try {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            assertThat(entered.poll(BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            recorder.record(entry()); // fills the queue; the listener is hung on event 1 throughout
            for (int i = 0; i < 100; i++) {
                recorder.record(entry());
            }
            assertThat(logged).isEmpty();
            listeners.reportDrops();
            assertThat(logged).hasSize(1);
            assertThat(logged.get(0)).startsWith("AUDIT_LISTENER_DROPPED").contains("100 audit events")
                    .doesNotContain("SUBJ-1").doesNotContain("CASE-1");
            listeners.reportDrops(); // nothing new
            assertThat(logged).hasSize(1);
            for (int i = 0; i < 50; i++) {
                recorder.record(entry());
            }
            listeners.reportDrops();
            assertThat(logged).hasSize(2);
            assertThat(logged.get(1)).contains("50 audit events").contains("150 in total");
        } finally {
            gate.release(1000);
            listeners.close();
        }
    }

    @Test
    void the_reporter_thread_reports_drops_by_itself_while_the_listener_is_still_hung() throws Exception {
        BlockingQueue<String> logged = new LinkedBlockingQueue<>();
        Lines lines = new Lines() {
            @Override void onWarn(String line) {
                logged.add(line);
            }
        };
        Semaphore gate = new Semaphore(0);
        BlockingQueue<Long> entered = new LinkedBlockingQueue<>();
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            entered.add(e.sequence());
            gate.acquireUninterruptibly();
        }), 1, Duration.ofSeconds(BOUND_SECONDS), Duration.ofMillis(5), lines.logger());
        try {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            assertThat(entered.poll(BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(1L);
            recorder.record(entry());
            recorder.record(entry()); // dropped
            String line = logged.poll(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(line).startsWith("AUDIT_LISTENER_DROPPED").contains("1 audit events");
            assertThat(gate.availablePermits()).isZero(); // the hung call has not returned
        } finally {
            gate.release(1000);
            listeners.close();
        }
    }

    @Test
    void a_blocking_log_appender_never_stalls_recording() throws Exception {
        CountDownLatch inLogger = new CountDownLatch(1);
        CountDownLatch releaseLogger = new CountDownLatch(1);
        Semaphore gate = new Semaphore(0);
        CountDownLatch started = new CountDownLatch(1);
        Lines lines = new Lines() {
            @Override void onWarn(String line) {
                inLogger.countDown();
                try {
                    releaseLogger.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            started.countDown();
            gate.acquireUninterruptibly();
        }), 1, Duration.ofSeconds(BOUND_SECONDS), Duration.ofMillis(5), lines.logger());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            awaitStarted(started);
            recorder.record(entry());
            recorder.record(entry()); // one drop
            // the reporter thread logs the drop and blocks inside the logger
            assertThat(inLogger.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    recorder.record(entry());
                }
            }).get(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(listeners.droppedCount()).isGreaterThan(100);
        } finally {
            releaseLogger.countDown();
            gate.release(1000);
            listeners.close();
            pool.shutdownNow();
        }
    }

    @Test
    void close_returns_even_if_an_appender_hangs_forever_inside_a_report_tick() throws Exception {
        CountDownLatch inLogger = new CountDownLatch(1);
        CountDownLatch releaseLogger = new CountDownLatch(1);
        Lines lines = new Lines() {
            @Override void onWarn(String line) {
                inLogger.countDown();
                while (true) { // ignores interruption: only the test's release ends it
                    try {
                        releaseLogger.await();
                        return;
                    } catch (InterruptedException e) {
                        // keep hanging
                    }
                }
            }
        };
        Semaphore gate = new Semaphore(0);
        CountDownLatch started = new CountDownLatch(1);
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            started.countDown();
            gate.acquireUninterruptibly();
        }), 1, Duration.ofMillis(50), Duration.ofMillis(5), lines.logger());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            awaitStarted(started);
            recorder.record(entry());
            recorder.record(entry()); // dropped
            assertThat(inLogger.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            recorder.record(entry()); // dropped again, never reported: the tick is stuck
            pool.submit(listeners::close).get(BOUND_SECONDS, TimeUnit.SECONDS);
            // two dropped, plus the queued event close() abandoned after the drain timeout
            assertThat(listeners.droppedCount()).isEqualTo(3);
        } finally {
            releaseLogger.countDown();
            gate.release(1000);
            pool.shutdownNow();
        }
    }

    @Test
    void close_returns_even_if_an_appender_hangs_forever_on_the_final_report() throws Exception {
        CountDownLatch inLogger = new CountDownLatch(1);
        CountDownLatch releaseLogger = new CountDownLatch(1);
        Lines lines = new Lines() {
            @Override void onWarn(String line) {
                inLogger.countDown();
                while (true) { // ignores interruption: only the test's release ends it
                    try {
                        releaseLogger.await();
                        return;
                    } catch (InterruptedException e) {
                        // keep hanging
                    }
                }
            }
        };
        Semaphore gate = new Semaphore(0);
        CountDownLatch started = new CountDownLatch(1);
        // the report interval is an hour: only close() reports, so the hang is on the closing path
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            started.countDown();
            gate.acquireUninterruptibly();
        }), 1, Duration.ofMillis(50), Duration.ofHours(1), lines.logger());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            awaitStarted(started);
            recorder.record(entry());
            recorder.record(entry()); // dropped
            pool.submit(listeners::close).get(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(inLogger.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(listeners.droppedCount()).isEqualTo(2);
        } finally {
            releaseLogger.countDown();
            gate.release(1000);
            pool.shutdownNow();
        }
    }

    @Test
    void a_listener_that_leaves_its_interrupt_flag_set_does_not_end_delivery() {
        List<Long> reinterrupting = Collections.synchronizedList(new ArrayList<>());
        List<Long> after = Collections.synchronizedList(new ArrayList<>());
        try (AuditEventListeners listeners = dispatcher(new Lines(), 100, e -> {
            reinterrupting.add(e.sequence());
            Thread.currentThread().interrupt();
        }, e -> after.add(e.sequence()))) {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            for (int i = 0; i < 20; i++) {
                recorder.record(entry());
            }
            listeners.close();
        }
        assertThat(reinterrupting).hasSize(20);
        assertThat(after).hasSize(20);
    }

    private static void awaitStarted(CountDownLatch started) {
        try {
            assertThat(started.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void close_drains_queued_events_then_stops_the_thread() {
        long before = dispatcherThreads();
        List<Long> seen = Collections.synchronizedList(new ArrayList<>());
        AuditEventListeners listeners = dispatcher(new Lines(), 100, e -> seen.add(e.sequence()));
        assertThat(dispatcherThreads()).isEqualTo(before + 2);
        AuditRecorder recorder = recorder(e -> { }, listeners);
        for (int i = 0; i < 50; i++) {
            recorder.record(entry());
        }
        listeners.close();
        assertThat(seen).hasSize(50);
        assertThat(dispatcherThreads()).isEqualTo(before);
    }

    @Test
    void repeated_start_and_stop_leaves_no_thread_behind() {
        long before = dispatcherThreads();
        for (int i = 0; i < 25; i++) {
            AuditEventListeners listeners = dispatcher(new Lines(), 4, e -> { });
            recorder(e -> { }, listeners).record(entry());
            listeners.close();
            listeners.close(); // idempotent
        }
        assertThat(dispatcherThreads()).isEqualTo(before);
    }

    @Test
    void close_on_an_interrupted_thread_still_writes_the_final_drop_line_and_keeps_the_flag() {
        Lines lines = new Lines() {
            @Override void onWarn(String line) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.onWarn(line);
            }
        };
        Semaphore gate = new Semaphore(0);
        CountDownLatch started = new CountDownLatch(1);
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            started.countDown();
            gate.acquireUninterruptibly();
        }), 1, Duration.ofMillis(50), Duration.ofHours(1), lines.logger());
        try {
            AuditRecorder recorder = recorder(e -> { }, listeners);
            recorder.record(entry());
            awaitStarted(started);
            recorder.record(entry()); // queued; abandoned by close()
            recorder.record(entry()); // dropped
            Thread.currentThread().interrupt();
            listeners.close();
            assertThat(lines.warnings).hasSize(1);
            assertThat(lines.warnings.get(0)).startsWith("AUDIT_LISTENER_DROPPED").contains("2 audit events");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            gate.release(1000);
            listeners.close();
        }
    }

    @Test
    void close_abandons_a_hung_listener_after_the_drain_timeout_and_logs_the_remainder_as_dropped() {
        Lines lines = new Lines();
        CountDownLatch started = new CountDownLatch(1);
        long before = dispatcherThreads();
        AuditEventListeners listeners = new AuditEventListeners(List.of(e -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(); // never released; only interruption ends it
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }), 8, Duration.ofMillis(50), Duration.ofHours(1), lines.logger());
        AuditRecorder recorder = recorder(e -> { }, listeners);
        recorder.record(entry());
        awaitStarted(started);
        for (int i = 0; i < 3; i++) {
            recorder.record(entry());
        }
        listeners.close();
        assertThat(dispatcherThreads()).isEqualTo(before);
        assertThat(listeners.droppedCount()).isEqualTo(3);
        assertThat(lines.warnings).hasSize(1);
        assertThat(lines.warnings.get(0)).startsWith("AUDIT_LISTENER_DROPPED").contains("3 audit events");
    }

    @Test
    void events_recorded_after_close_are_counted_as_dropped_and_recording_still_works() {
        Lines lines = new Lines();
        AuditEventListeners listeners = dispatcher(lines, 4, e -> { });
        AuditRecorder recorder = recorder(e -> { }, listeners);
        listeners.close();
        assertThat(recorder.record(entry()).sequence()).isEqualTo(1);
        assertThat(listeners.droppedCount()).isEqualTo(1);
    }

    @Test
    void without_listeners_no_thread_starts_and_recording_is_unchanged() {
        long before = dispatcherThreads();
        AuditEventListeners none = new AuditEventListeners(List.of(), 4);
        assertThat(none.active()).isFalse();
        assertThat(dispatcherThreads()).isEqualTo(before);
        assertThat(recorder(e -> { }, none).record(entry()).sequence()).isEqualTo(1);
        assertThat(none.droppedCount()).isZero();
    }

    @Test
    void a_non_positive_capacity_is_refused() {
        assertThatThrownBy(() -> new AuditEventListeners(List.of(e -> { }), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
