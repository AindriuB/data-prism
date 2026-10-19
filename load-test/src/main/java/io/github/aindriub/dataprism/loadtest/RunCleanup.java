package io.github.aindriub.dataprism.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stops what a run started and removes its temporary directory (keystores included). It runs from the normal
 * {@code finally} path and from a JVM shutdown hook (Ctrl-C, SIGTERM), so it is idempotent: the first call
 * does the work and any later call, from either path, does nothing.
 */
final class RunCleanup implements Runnable {

    private final Path dir;
    private final Deque<AutoCloseable> started = new ArrayDeque<>();
    private boolean begun;

    RunCleanup(Path dir) {
        this.dir = dir;
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

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort: keep stopping the rest.
        }
    }

    private synchronized AutoCloseable pop() {
        return started.poll();
    }
}
