package io.github.aindriub.dataprism.loadtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Waits that end. A harness that can hang in cleanup or in a helper process is worse than one that fails, so
 * every blocking step goes through here with an explicit bound.
 */
final class Bounded {

    private Bounded() { }

    /**
     * Runs {@code task} on a daemon thread and waits at most {@code bound} for it. Returns whether it finished;
     * a task that does not is abandoned (interrupted, then left behind) and the caller carries on. A task that
     * throws counts as finished: the exception is the task's own business.
     */
    static boolean run(Runnable task, Duration bound) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (RuntimeException | Error ignored) {
                // Best effort by contract; see above.
            }
        }, "load-test-bounded");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(Math.max(1, bound.toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            thread.interrupt();
            return false;
        }
        return true;
    }

    /**
     * The trimmed output (stdout and stderr) of a command that exits 0 within {@code bound}.
     *
     * @throws IOException if the command cannot start, exits non-zero (the message carries its output), or does
     *                     not finish within {@code bound}, in which case it is killed
     */
    static String command(List<String> command, Duration bound) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try {
                process.getInputStream().transferTo(captured);
            } catch (IOException ignored) {
                // The process ended or was killed; what was read is what we have.
            }
        }, "load-test-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(bound.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IOException(command.get(0) + " did not finish within " + bound);
            }
            drain.join(5_000);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        String output;
        synchronized (captured) {
            output = captured.toString(StandardCharsets.UTF_8).trim();
        }
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " exited with " + process.exitValue() + ": " + output);
        }
        return output;
    }
}
