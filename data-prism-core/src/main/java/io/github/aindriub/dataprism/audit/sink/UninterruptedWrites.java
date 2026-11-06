package io.github.aindriub.dataprism.audit.sink;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs audit-file channel work on an internal daemon thread that no caller can interrupt, because an
 * interrupt delivered to a thread inside {@code FileChannel.write}, {@code force} or {@code size} closes
 * the channel. The caller waits uninterruptibly and gets its interrupt flag back, set, when it returns.
 *
 * <p>An exception thrown by the work is rethrown to the caller unchanged. If the work cannot be handed
 * to the internal thread at all, {@link HandOffException} is thrown and nothing has been written.
 * There is deliberately no timeout: a hung disk holds the request, it never returns it unaudited.
 */
final class UninterruptedWrites {

    /** The work could not be started on the internal thread. */
    static final class HandOffException extends RuntimeException {
        HandOffException(Throwable cause) {
            super("audit write could not be handed to the writer thread", cause);
        }
    }

    static final UninterruptedWrites SHARED = new UninterruptedWrites(newPool());

    private final Executor executor;

    UninterruptedWrites(Executor executor) {
        this.executor = executor;
    }

    private static Executor newPool() {
        AtomicInteger n = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread t = new Thread(task, "audit-sink-writer-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        return pool;
    }

    <T> T call(Supplier<T> work) {
        FutureTask<T> task = new FutureTask<>(work::get);
        try {
            executor.execute(task);
        } catch (RuntimeException | Error e) {
            // An Error counts too: Thread.start can throw OutOfMemoryError ("unable to create native
            // thread"), and nothing was written, so it must poison the sink like any failed hand-off.
            throw new HandOffException(e);
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException r) {
                        throw r;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }
}
