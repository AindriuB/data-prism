package io.github.aindriub.dataprism.loadtest;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Watches the server child while a run drives it. A run of many hours against a server that has died would
 * only record connection failures until its deadline; this notices and tells the workers to stop.
 */
final class ServerWatch implements AutoCloseable {

    private final Supplier<Integer> exitCode;
    private final Thread thread;
    private volatile Integer exited;

    private ServerWatch(Supplier<Integer> exitCode, Duration interval) {
        this.exitCode = exitCode;
        thread = new Thread(() -> {
            try {
                while (exited == null) {
                    Integer code = exitCode.get();
                    if (code != null) {
                        exited = code;
                        return;
                    }
                    Thread.sleep(Math.max(1, interval.toMillis()));
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }, "load-test-server-watch");
        thread.setDaemon(true);
    }

    /** @param exitCode the server's exit code, or {@code null} while it is alive */
    static ServerWatch start(Supplier<Integer> exitCode, Duration interval) {
        ServerWatch watch = new ServerWatch(exitCode, interval);
        watch.thread.start();
        return watch;
    }

    boolean halted() {
        return exited != null;
    }

    /** The exit code of a server that died while watched, otherwise {@code null}. */
    Integer exitCode() {
        return exited;
    }

    @Override
    public void close() {
        thread.interrupt();
    }
}
