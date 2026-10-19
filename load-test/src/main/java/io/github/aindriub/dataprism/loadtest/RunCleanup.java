package io.github.aindriub.dataprism.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops what a run started and removes its temporary directory (keystores included). It runs from the normal
 * {@code finally} path and from a JVM shutdown hook (Ctrl-C, SIGTERM), so it is idempotent: the first call
 * does the work and any later call, from either path, does nothing.
 */
final class RunCleanup implements Runnable {

    private final Path dir;
    private final Deque<AutoCloseable> started = new ArrayDeque<>();
    private final AtomicBoolean done = new AtomicBoolean();

    RunCleanup(Path dir) {
        this.dir = dir;
    }

    /** Registers something to close on cleanup, most recently tracked first. */
    synchronized void track(AutoCloseable closeable) {
        started.push(closeable);
    }

    @Override
    public void run() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        AutoCloseable next;
        while ((next = pop()) != null) {
            try {
                next.close();
            } catch (Exception ignored) {
                // Best effort: keep stopping the rest.
            }
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
            // Temporary files only; a leftover directory under the OS temp dir is harmless.
        }
    }

    private synchronized AutoCloseable pop() {
        return started.poll();
    }
}
