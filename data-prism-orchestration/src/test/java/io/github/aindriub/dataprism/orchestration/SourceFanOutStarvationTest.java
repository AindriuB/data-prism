package io.github.aindriub.dataprism.orchestration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.SourceCallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source calls run on dedicated platform threads, bounded in total and per source, so an adapter that
 * ignores interrupts cannot starve the shared virtual-thread scheduler (task 272).
 */
class SourceFanOutStarvationTest {

    private static final long WAIT_SECONDS = 10;

    /** Loops until released, ignoring interrupts and never blocking. */
    private static final class Spinner implements DataSourceAdapter<String> {
        private final String name;
        private final CountDownLatch entered;
        private final AtomicInteger calls = new AtomicInteger();
        private final Release release;

        Spinner(String name, CountDownLatch entered, Release release) {
            this.name = name;
            this.entered = entered;
            this.release = release;
        }

        @Override public String sourceName() { return name; }
        @Override public Class<String> responseType() { return String.class; }

        @Override
        public String fetch(DataRequest request) {
            calls.incrementAndGet();
            entered.countDown();
            while (!release.released) {
                Thread.onSpinWait();
            }
            return "late";
        }
    }

    private static final class Release {
        volatile boolean released;
    }

    private static final class Quick implements DataSourceAdapter<String> {
        private final String name;
        final AtomicInteger calls = new AtomicInteger();

        Quick(String name) {
            this.name = name;
        }

        @Override public String sourceName() { return name; }
        @Override public Class<String> responseType() { return String.class; }

        @Override
        public String fetch(DataRequest request) {
            calls.incrementAndGet();
            return "ok";
        }
    }

    private static final class RecordingMetrics implements PrivacyMetrics {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void increment(Metric metric) { events.add(metric.name()); }
        @Override public void increment(Metric metric, String sourceName) { events.add(metric.name() + ":" + sourceName); }
        @Override public void record(Metric metric, String sourceName, Duration duration) {
            events.add(metric.name() + ":" + sourceName);
        }

        long count(String event) {
            return events.stream().filter(event::equals).count();
        }
    }

    private static Map<String, DataRequest> ask(String... sources) {
        Map<String, DataRequest> asks = new java.util.HashMap<>();
        for (String source : sources) {
            asks.put(source, DataRequest.of("THING", "1"));
        }
        return asks;
    }

    private static List<DataSourceAdapter<?>> adapters(DataSourceAdapter<?>... adapters) {
        return List.of(adapters);
    }

    private static SourceFanOut fanOut(SourceCallSlots slots) {
        return new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults(), slots);
    }

    private static RequestLimits limits(Duration perSource) {
        return TestLimits.defaults().perSourceTimeout(perSource).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private static Thread start(Runnable work) {
        Thread thread = new Thread(work);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void finish(Release release, List<Thread> threads) throws InterruptedException {
        release.released = true;
        for (Thread thread : threads) {
            thread.join(5000);
            assertThat(thread.isAlive()).as("thread %s ended", thread.getName()).isFalse();
        }
    }

    private static void awaitPermits(SourceCallSlots slots, int pool, String source, int own) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((slots.poolPermits() != pool || slots.sourcePermits(source) != own) && System.nanoTime() < limit) {
            Thread.onSpinWait();
        }
        assertThat(slots.poolPermits()).as("pool permits").isEqualTo(pool);
        assertThat(slots.sourcePermits(source)).as("permits of " + source).isEqualTo(own);
    }

    @Test
    @DisplayName("spinning adapters, one per core, do not stop another source answering")
    void spinnersDoNotStarveAnotherSource() throws Exception {
        int n = Runtime.getRuntime().availableProcessors();
        var slots = new SourceCallSlots(n + 4, n);
        var fanOut = fanOut(slots);
        var limits = limits(Duration.ofMillis(200));
        var release = new Release();
        var entered = new CountDownLatch(n);
        List<Thread> threads = new ArrayList<>();
        var probeResult = new AtomicReference<List<SourceFanOut.Fetched>>();
        try {
            for (int i = 0; i < n; i++) {
                var spinner = new Spinner("spin", entered, release);
                threads.add(start(() -> fanOut.fetchAll(adapters(spinner), ask("spin"), limits)));
            }
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("every spinner is spinning").isTrue();
            Thread probe = start(() -> probeResult.set(
                    fanOut.fetchAll(adapters(new Quick("probe")), ask("probe"), limits)));
            threads.add(probe);
            probe.join(1200);
            if (probe.isAlive()) {
                release.released = true;
                probe.join(5000);
                throw new AssertionError("probe did not return within 1,200 ms");
            }
            assertThat(probeResult.get()).singleElement().satisfies(f ->
                    assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED));
        } finally {
            finish(release, threads);
        }
    }

    @Test
    @DisplayName("a source cannot take more than its ceiling; the excess is UNAVAILABLE, not called; other sources answer")
    void perSourceCeiling() throws Exception {
        var slots = new SourceCallSlots(8, 2);
        var fanOut = fanOut(slots);
        var limits = limits(Duration.ofSeconds(20));
        var release = new Release();
        var entered = new CountDownLatch(2);
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                var spinner = new Spinner("a", entered, release);
                threads.add(start(() -> fanOut.fetchAll(adapters(spinner), ask("a"), limits)));
            }
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            var third = new Spinner("a", new CountDownLatch(1), release);
            var b = new Quick("b");
            var results = fanOut.fetchAll(adapters(third, b), ask("a", "b"), limits);
            assertThat(results).hasSize(2);
            var byName = new java.util.HashMap<String, SourceOutcome>();
            results.forEach(f -> byName.put(f.outcome().sourceName(), f.outcome()));
            assertThat(byName.get("a").status()).isEqualTo(SourceOutcome.Status.FAILED);
            assertThat(byName.get("a").detail()).isEqualTo("UNAVAILABLE");
            assertThat(third.calls.get()).as("the refused adapter was not called").isZero();
            assertThat(byName.get("b").status()).isEqualTo(SourceOutcome.Status.ANSWERED);
            var later = fanOut.fetchAll(adapters(b), ask("b"), limits);
            assertThat(later.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
        } finally {
            finish(release, threads);
        }
    }

    @Test
    @DisplayName("a full pool refuses a third source: UNAVAILABLE, counted as saturated, no error, no breaker record")
    void poolSaturation() throws Exception {
        var slots = new SourceCallSlots(2, 2);
        var metrics = new RecordingMetrics();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(5), Clock.systemUTC());
        var fanOut = new SourceFanOut(breaker, Clock.systemUTC(),
                SourceFanOutOptions.defaults().withMetrics(metrics), slots);
        var limits = limits(Duration.ofSeconds(20));
        var release = new Release();
        var entered = new CountDownLatch(2);
        List<Thread> threads = new ArrayList<>();
        try {
            for (String name : List.of("a", "b")) {
                var spinner = new Spinner(name, entered, release);
                threads.add(start(() -> fanOut.fetchAll(adapters(spinner), ask(name), limits)));
            }
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            var third = new Quick("c");
            var results = fanOut.fetchAll(adapters(third), ask("c"), limits);
            assertThat(results).singleElement().satisfies(f -> {
                assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED);
                assertThat(f.outcome().detail()).isEqualTo("UNAVAILABLE");
            });
            assertThat(third.calls.get()).isZero();
            assertThat(metrics.count("SOURCE_CALLS_SATURATED:c")).isEqualTo(1);
            assertThat(metrics.count("SOURCE_ERRORS:c")).isZero();
            assertThat(metrics.count("SOURCE_LATENCY:c")).isZero();
            release.released = true;
            awaitPermits(slots, 2, "c", 2);
            // Threshold 1: had the breaker recorded the refusal as a failure, c would now be open.
            var after = fanOut.fetchAll(adapters(third), ask("c"), limits);
            assertThat(after.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
        } finally {
            finish(release, threads);
        }
    }

    @Test
    @DisplayName("a fetchAll with an interrupt-ignoring adapter returns within its timeout plus a second, TIMED_OUT")
    void returnsOnTimeWithADeafAdapter() throws Exception {
        var slots = new SourceCallSlots(8, 4);
        var fanOut = fanOut(slots);
        var release = new Release();
        var spinner = new Spinner("spin", new CountDownLatch(1), release);
        long began = System.nanoTime();
        try {
            var results = fanOut.fetchAll(adapters(spinner), ask("spin"), limits(Duration.ofMillis(200)));
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertThat(results.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
            assertThat(tookMillis).isLessThan(1200);
            assertThat(slots.poolPermits()).as("the timed-out call still holds its slots").isEqualTo(7);
            assertThat(slots.sourcePermits("spin")).isEqualTo(3);
        } finally {
            release.released = true;
        }
        awaitPermits(slots, 8, "spin", 4);
    }

    @Test
    @DisplayName("after mixed calls, every pool and per-source permit is free")
    void slotAccounting() throws Exception {
        var slots = new SourceCallSlots(16, 8);
        var fanOut = fanOut(slots);
        var release = new Release();
        var failing = new DataSourceAdapter<String>() {
            @Override public String sourceName() { return "failing"; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) { throw new IllegalStateException("x"); }
        };
        var quick = new Quick("quick");
        for (int i = 0; i < 1000; i++) {
            Spinner spinner = new Spinner("spin", new CountDownLatch(1), release);
            boolean deaf = i % 250 == 0;
            var limits = limits(deaf ? Duration.ofMillis(50) : Duration.ofSeconds(20));
            if (deaf) {
                var results = fanOut.fetchAll(adapters(spinner), ask("spin"), limits);
                assertThat(results.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
                assertThat(slots.sourcePermits("spin")).as("held until the call returns").isLessThan(8);
                release.released = true;
                awaitPermits(slots, 16, "spin", 8);
                release.released = false;
            } else {
                var results = fanOut.fetchAll(adapters(quick, failing), ask("quick", "failing"), limits);
                assertThat(results).hasSize(2);
            }
        }
        awaitPermits(slots, 16, "quick", 8);
        awaitPermits(slots, 16, "failing", 8);
        awaitPermits(slots, 16, "spin", 8);
    }

    @Test
    @DisplayName("a thread that will not start: UNAVAILABLE, nothing counted, slots back, one ERROR each, later calls answer")
    void threadStartFailure() throws Exception {
        var failing = new AtomicReference<ThreadFactory>(r -> {
            throw new OutOfMemoryError("unable to create native thread");
        });
        ThreadFactory real = r -> new Thread(r);
        var factory = new AtomicInteger();
        ThreadFactory chosen = r -> {
            factory.incrementAndGet();
            return failing.get().newThread(r);
        };
        var slots = new SourceCallSlots(4, 4, chosen);
        var metrics = new RecordingMetrics();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(5), Clock.systemUTC());
        var fanOut = new SourceFanOut(breaker, Clock.systemUTC(),
                SourceFanOutOptions.defaults().withMetrics(metrics), slots);
        var logger = (Logger) LoggerFactory.getLogger(SourceCallSlots.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var quick = new Quick("q");
            for (int i = 0; i < 3; i++) {
                var results = fanOut.fetchAll(adapters(quick), ask("q"), limits(Duration.ofSeconds(5)));
                assertThat(results).singleElement().satisfies(f -> {
                    assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED);
                    assertThat(f.outcome().detail()).isEqualTo("UNAVAILABLE");
                });
            }
            // An already-started thread: start() throws IllegalThreadStateException.
            failing.set(r -> {
                Thread t = new Thread(() -> { });
                t.start();
                return t;
            });
            fanOut.fetchAll(adapters(quick), ask("q"), limits(Duration.ofSeconds(5)));
            assertThat(quick.calls.get()).isZero();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count()).isEqualTo(4);
            assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("native thread");
            assertThat(metrics.events).isEmpty();
            assertThat(slots.poolPermits()).isEqualTo(4);
            assertThat(slots.sourcePermits("q")).isEqualTo(4);
            failing.set(real);
            var after = fanOut.fetchAll(adapters(quick), ask("q"), limits(Duration.ofSeconds(5)));
            assertThat(after.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static final ThreadLocal<String> OWN = new ThreadLocal<>();
    private static final InheritableThreadLocal<String> INHERITABLE = new InheritableThreadLocal<>();

    @Test
    @DisplayName("each call runs on a fresh named platform daemon thread with no thread-locals and its own MDC")
    void threadIsolation() throws Exception {
        var slots = new SourceCallSlots(1, 1);
        var key = "transaction_id";
        var mdc = CorrelationMdc.of(key);
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(),
                SourceFanOutOptions.defaults().withMdc(mdc), slots);
        var seenOwn = Collections.synchronizedList(new ArrayList<String>());
        var seenInherited = Collections.synchronizedList(new ArrayList<String>());
        var seenMdc = new AtomicReference<String>();
        var thread = new AtomicReference<Thread>();
        var captured = new AtomicReference<Map<String, String>>();
        var capturedTimes = new AtomicInteger();
        slots.afterCallHook(() -> {
            captured.set(MDC.getCopyOfContextMap());
            capturedTimes.incrementAndGet();
        });
        var adapter = new DataSourceAdapter<String>() {
            @Override public String sourceName() { return "iso"; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) {
                seenOwn.add(OWN.get());
                seenInherited.add(INHERITABLE.get());
                seenMdc.set(MDC.get(key));
                thread.set(Thread.currentThread());
                OWN.set("mine");
                return "x";
            }
        };
        var id = CorrelationIdPolicy.opaque("[A-Za-z0-9-]{1,64}").validate("synthetic-clid-0001");
        var request = DataRequest.of("THING", "1").withContext(new SourceCallContext(id));
        INHERITABLE.set("requester");
        try {
            for (int i = 0; i < 2; i++) {
                if (i == 1) {
                    awaitPermits(slots, 1, "iso", 1);
                    awaitHook(capturedTimes, 1);
                }
                var results = fanOut.fetchAll(adapters(adapter), Map.of("iso", request), limits(Duration.ofSeconds(5)));
                assertThat(results.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
            }
            awaitPermits(slots, 1, "iso", 1);
            awaitHook(capturedTimes, 2);
        } finally {
            INHERITABLE.remove();
        }
        assertThat(seenOwn).containsExactly(null, null);
        assertThat(seenInherited).containsExactly(null, null);
        assertThat(seenMdc.get()).isEqualTo("synthetic-clid-0001");
        assertThat(thread.get().getName()).startsWith("dataprism-source-");
        assertThat(thread.get().isDaemon()).isTrue();
        assertThat(thread.get().isVirtual()).isFalse();
        assertThat(captured.get()).satisfiesAnyOf(m -> assertThat(m).isNull(), m -> assertThat(m).isEmpty());
    }

    @Test
    @DisplayName("the adapter sees the requester's context class loader")
    void contextClassLoaderIsKept() throws Exception {
        var slots = new SourceCallSlots(1, 1);
        var fanOut = fanOut(slots);
        var seen = new AtomicReference<ClassLoader>();
        var adapter = new DataSourceAdapter<String>() {
            @Override public String sourceName() { return "cl"; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) {
                seen.set(Thread.currentThread().getContextClassLoader());
                return "x";
            }
        };
        var custom = new ClassLoader(getClass().getClassLoader()) { };
        var original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(custom);
        try {
            var results = fanOut.fetchAll(adapters(adapter), ask("cl"), limits(Duration.ofSeconds(5)));
            assertThat(results.get(0).outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        assertThat(seen.get()).isSameAs(custom);
    }

    @Test
    @DisplayName("a refused call's read is reported as not started: saturated and start-failed")
    void refusedCallsAreNotStarted() throws Exception {
        var slots = new SourceCallSlots(1, 1);
        var fanOut = fanOut(slots);
        var limits = limits(Duration.ofSeconds(20));
        var release = new Release();
        var entered = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            var spinner = new Spinner("a", entered, release);
            threads.add(start(() -> fanOut.fetchAll(adapters(spinner), ask("a"), limits)));
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            var quick = new Quick("a");
            var saturated = fanOut.execute(fanOut.plan(adapters(quick), ask("a"), limits), limits);
            assertThat(saturated.notStarted()).isEqualTo(1);
            assertThat(quick.calls.get()).isZero();
        } finally {
            finish(release, threads);
        }
        var broken = new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(),
                SourceFanOutOptions.defaults(), new SourceCallSlots(1, 1, r -> {
                    throw new OutOfMemoryError("unable to create native thread");
                }));
        var quick = new Quick("q");
        var failed = broken.execute(broken.plan(adapters(quick), ask("q"), limits), limits);
        assertThat(failed.notStarted()).isEqualTo(1);
        assertThat(quick.calls.get()).isZero();
    }

    private static void awaitHook(AtomicInteger times, int expected) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (times.get() < expected && System.nanoTime() < limit) {
            Thread.onSpinWait();
        }
        assertThat(times.get()).isGreaterThanOrEqualTo(expected);
    }
}
