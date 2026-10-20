package io.github.aindriub.dataprism.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stops what a run started and removes its temporary directory (keystores included). It runs from the normal
 * {@code finally} path and from a JVM shutdown hook (Ctrl-C, SIGTERM), so it is idempotent: the first call
 * does the work and any later call, from either path, does nothing.
 */
final class RunCleanup implements Runnable {

    /** The longest any one thing may take to close before cleanup carries on without it. */
    static final Duration CLOSE_BOUND = Duration.ofSeconds(30);

    private final Path dir;
    private final Duration closeBound;
    private final Deque<AutoCloseable> started = new ArrayDeque<>();
    private boolean begun;

    RunCleanup(Path dir) {
        this(dir, CLOSE_BOUND);
    }

    RunCleanup(Path dir, Duration closeBound) {
        this.dir = dir;
        this.closeBound = closeBound;
    }

    /**
     * Registers something to close on cleanup, most recently tracked first. If cleanup has already begun
     * (a signal can land while a resource is being launched) it is closed at once instead of queued.
     */
    void track(AutoCloseable closeable) {
        synchronized (this) {
            if (!begun) {
                started.push(closeable);
                return;
            }
        }
        closeQuietly(closeable);
    }

    @Override
    public void run() {
        synchronized (this) {
            if (begun) {
                return;
            }
            begun = true;
        }
        AutoCloseable next;
        while ((next = pop()) != null) {
            closeQuietly(next);
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
            // Temporary files only; a leftover directory under the OS temp dir is harmless.
        }
    }

    /** Best effort, and bounded: a close that hangs is abandoned so the rest still stop and the JVM can exit. */
    private void closeQuietly(AutoCloseable closeable) {
        Bounded.run(() -> {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Best effort: keep stopping the rest.
            }
        }, closeBound);
    }

    private synchronized AutoCloseable pop() {
        return started.poll();
    }
}
