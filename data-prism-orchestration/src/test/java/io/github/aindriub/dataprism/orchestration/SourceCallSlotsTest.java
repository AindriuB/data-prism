package io.github.aindriub.dataprism.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCallSlotsTest {

    private static final Runnable NOTHING = () -> { };

    private static Runnable waitFor(CountDownLatch hold) {
        return () -> {
            try {
                hold.await();
            } catch (InterruptedException ignored) {
                // test
            }
        };
    }

    @Test
    @DisplayName("the shipped sizes are 128 for the pool and 32 per source")
    void sizes() {
        var slots = new SourceCallSlots();
        assertThat(slots.poolPermits()).isEqualTo(128);
        assertThat(slots.sourcePermits("a")).isEqualTo(32);
    }

    @Test
    @DisplayName("a slot is held until the task returns, then released once; the thread is a named platform daemon")
    void heldUntilTheTaskReturns() throws Exception {
        var slots = new SourceCallSlots(2, 2);
        var hold = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        var start = slots.acquireAndStart("a", waitFor(hold), done::countDown, thread::set);
        assertThat(start).isEqualTo(SourceCallSlots.Start.STARTED);
        assertThat(slots.poolPermits()).isEqualTo(1);
        assertThat(slots.sourcePermits("a")).isEqualTo(1);
        assertThat(thread.get().getName()).startsWith("dataprism-source-");
        assertThat(thread.get().isDaemon()).isTrue();
        assertThat(thread.get().isVirtual()).isFalse();
        hold.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(slots.poolPermits()).isEqualTo(2);
        assertThat(slots.sourcePermits("a")).isEqualTo(2);
    }

    @Test
    @DisplayName("a full source ceiling or a full pool refuses, and holds nothing")
    void saturation() {
        var slots = new SourceCallSlots(2, 1);
        var hold = new CountDownLatch(1);
        assertThat(slots.acquireAndStart("a", waitFor(hold), NOTHING, t -> { }))
                .isEqualTo(SourceCallSlots.Start.STARTED);
        assertThat(slots.acquireAndStart("a", waitFor(hold), NOTHING, t -> { }))
                .isEqualTo(SourceCallSlots.Start.SATURATED);
        assertThat(slots.acquireAndStart("b", waitFor(hold), NOTHING, t -> { }))
                .isEqualTo(SourceCallSlots.Start.STARTED);
        assertThat(slots.acquireAndStart("c", waitFor(hold), NOTHING, t -> { }))
                .isEqualTo(SourceCallSlots.Start.SATURATED);
        assertThat(slots.sourcePermits("c")).as("the refused source's slot went back").isEqualTo(1);
        assertThat(slots.poolPermits()).isZero();
        hold.countDown();
    }

    @Test
    @DisplayName("a thread that cannot be created or started gives the slots back at once")
    void startFailure() {
        var slots = new SourceCallSlots(2, 2, r -> {
            throw new OutOfMemoryError("unable to create native thread");
        });
        var ran = new AtomicBoolean();
        assertThat(slots.acquireAndStart("a", () -> ran.set(true), NOTHING, t -> { }))
                .isEqualTo(SourceCallSlots.Start.START_FAILED);
        assertThat(slots.poolPermits()).isEqualTo(2);
        assertThat(slots.sourcePermits("a")).isEqualTo(2);

        var started = new SourceCallSlots(2, 2, r -> {
            Thread t = new Thread(() -> { });
            t.start();
            return t;
        });
        assertThat(started.acquireAndStart("a", () -> ran.set(true), NOTHING, t -> { }))
                .isEqualTo(SourceCallSlots.Start.START_FAILED);
        assertThat(started.poolPermits()).isEqualTo(2);
        assertThat(started.sourcePermits("a")).isEqualTo(2);
        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("1,000 calls, some failing, leave every permit free")
    void accounting() throws Exception {
        var slots = new SourceCallSlots(8, 4);
        var done = new AtomicInteger();
        var refused = new AtomicInteger();
        for (int i = 0; i < 1000; i++) {
            int n = i;
            var finished = new CountDownLatch(1);
            var start = slots.acquireAndStart("s" + (i % 3), () -> {
                if (n % 5 == 0) {
                    throw new IllegalStateException("fails");
                }
            }, () -> {
                done.incrementAndGet();
                finished.countDown();
            }, t -> t.setUncaughtExceptionHandler((x, e) -> { }));
            if (start == SourceCallSlots.Start.STARTED) {
                assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            } else {
                refused.incrementAndGet();
            }
        }
        assertThat(done.get() + refused.get()).isEqualTo(1000);
        assertThat(slots.poolPermits()).isEqualTo(8);
        for (int s = 0; s < 3; s++) {
            assertThat(slots.sourcePermits("s" + s)).isEqualTo(4);
        }
    }
}
