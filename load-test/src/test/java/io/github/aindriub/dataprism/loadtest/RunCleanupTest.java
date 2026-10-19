package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunCleanupTest {

    @TempDir
    Path parent;

    @Test
    void closesTrackedThingsNewestFirstAndDeletesTheDirectory() throws Exception {
        Path dir = Files.createDirectory(parent.resolve("run"));
        Files.writeString(dir.resolve("jwks-server.p12"), "x");
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/f"), "y");
        List<String> order = new ArrayList<>();
        RunCleanup cleanup = new RunCleanup(dir);
        cleanup.track(() -> order.add("first"));
        cleanup.track(() -> order.add("second"));

        cleanup.run();

        assertThat(order).containsExactly("second", "first");
        assertThat(dir).doesNotExist();
    }

    @Test
    void isIdempotentSoTheShutdownHookAndTheFinallyPathCanBothRunIt() throws Exception {
        Path dir = Files.createDirectory(parent.resolve("run"));
        List<String> closes = new ArrayList<>();
        RunCleanup cleanup = new RunCleanup(dir);
        cleanup.track(() -> closes.add("server"));

        Thread hook = new Thread(cleanup);
        hook.start();
        hook.join();
        cleanup.run();

        assertThat(closes).containsExactly("server");
        assertThat(dir).doesNotExist();
    }

    @Test
    void aFailingCloseDoesNotStopTheRestOrTheDeletion() throws Exception {
        Path dir = Files.createDirectory(parent.resolve("run"));
        List<String> closes = new ArrayList<>();
        RunCleanup cleanup = new RunCleanup(dir);
        cleanup.track(() -> closes.add("later"));
        cleanup.track(() -> {
            throw new IllegalStateException("boom");
        });

        cleanup.run();

        assertThat(closes).containsExactly("later");
        assertThat(dir).doesNotExist();
    }

    @Test
    void closesAThingTrackedAfterCleanupHasRunInsteadOfQueueingIt() throws Exception {
        Path dir = Files.createDirectory(parent.resolve("run"));
        List<String> closes = new ArrayList<>();
        RunCleanup cleanup = new RunCleanup(dir);
        cleanup.run();

        cleanup.track(() -> closes.add("late"));

        assertThat(closes).containsExactly("late");
    }

    @Test
    void aTrackRacingWithRunNeverLeavesAThingUnclosed() throws Exception {
        for (int round = 0; round < 200; round++) {
            Path dir = Files.createDirectory(parent.resolve("race" + round));
            RunCleanup cleanup = new RunCleanup(dir);
            java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            Thread tracker = new Thread(() -> {
                await(start);
                cleanup.track(closes::incrementAndGet);
            });
            Thread runner = new Thread(() -> {
                await(start);
                cleanup.run();
            });
            tracker.start();
            runner.start();
            tracker.join();
            runner.join();
            cleanup.run();

            assertThat(closes.get()).as("round %d", round).isEqualTo(1);
        }
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
