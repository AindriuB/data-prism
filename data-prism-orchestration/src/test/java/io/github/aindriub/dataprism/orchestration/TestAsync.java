package io.github.aindriub.dataprism.orchestration;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Runs a blocking request on a thread of its own.
 *
 * <p>{@code CompletableFuture.supplyAsync} would run it on the common pool, whose size follows the number of
 * cores: on a two-core CI runner that is one thread, and a request that blocks on a latch holds it, so the next
 * request waits behind it and every later test in the JVM with it.
 */
final class TestAsync {

    private TestAsync() {
    }

    static <T> CompletableFuture<T> supply(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("test-request").start(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    static CompletableFuture<Void> run(Runnable work) {
        return supply(() -> {
            work.run();
            return null;
        });
    }
}
