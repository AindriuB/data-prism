package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LaunchGuardTest {

    private static final class StubProcess extends Process {
        final AtomicBoolean killed = new AtomicBoolean();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            killed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            killed.set(true);
            return this;
        }
    }

    private static boolean parkedOrDone(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING
                || state == Thread.State.TERMINATED;
    }

    @Test
    @Timeout(30)
    void aLateLaunchedKillsTheChildWhenCloseCameFirst() {
        ServerProcess.LaunchGuard guard = new ServerProcess.LaunchGuard(Duration.ZERO);
        StubProcess child = new StubProcess();

        guard.close();
        assertThat(child.killed).isFalse();
        guard.launched(child);

        assertThat(child.killed).isTrue();
    }

    @Test
    @Timeout(30)
    void closeAfterLaunchedKillsTheChild() {
        ServerProcess.LaunchGuard guard = new ServerProcess.LaunchGuard();
        StubProcess child = new StubProcess();

        guard.launched(child);
        guard.close();

        assertThat(child.killed).isTrue();
    }

    @Test
    @Timeout(30)
    void closeWhileLaunchingBlocksUntilLaunchedThenKills() throws Exception {
        ServerProcess.LaunchGuard guard = new ServerProcess.LaunchGuard(Duration.ofSeconds(30));
        StubProcess child = new StubProcess();
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            guard.close();
            closeReturned.countDown();
        });
        closer.start();
        // Wait until the closer is parked inside close() (waiting on the monitor), without sleeping.
        while (!parkedOrDone(closer)) {
            Thread.onSpinWait();
        }
        assertThat(closeReturned.getCount()).isEqualTo(1);

        guard.launched(child);
        closer.join();

        assertThat(child.killed).isTrue();
    }

    @Test
    @Timeout(30)
    void failedReleasesCloseWithoutWaitingForTheBound() throws Exception {
        ServerProcess.LaunchGuard guard = new ServerProcess.LaunchGuard(Duration.ofMinutes(5));
        Thread closer = new Thread(guard::close);
        closer.start();
        while (!parkedOrDone(closer)) {
            Thread.onSpinWait();
        }

        guard.failed();
        closer.join();

        assertThat(closer.isAlive()).isFalse();
    }
}
