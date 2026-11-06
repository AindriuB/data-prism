package io.github.aindriub.dataprism.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The bounded set of platform threads that run source calls.
 *
 * <p>An adapter call that ignores interrupts, or pins its carrier, must not be able to starve the
 * shared virtual-thread scheduler, so calls run on platform daemon threads, a fresh one per call,
 * bounded by two kinds of slot. A slot is held until the adapter call returns, which may be long
 * after its timeout. A slot is only ever taken with {@code tryAcquire}: when none is free the source
 * is not called, and the caller reports it at once.
 *
 * <p>A fresh thread per call exits when the call returns, so nothing a call put in a
 * {@code ThreadLocal} is seen by another, and it is created without inheriting the requesting
 * thread's {@code InheritableThreadLocal}s.
 */
final class SourceCallSlots {

    /**
     * Calls in flight in the whole process: 32 concurrent requests each running
     * {@code RequestLimits.DEFAULT.maxConcurrency()} (4) calls. Each platform thread reserves a native
     * stack, so the bound is fixed rather than elastic.
     */
    static final int POOL_SIZE = 128;

    /**
     * Calls in flight for one source: the number of concurrent requests the pool is sized for (128 / 4).
     * A request calls a given source at most once at a time, so all 32 of those requests can call the same
     * healthy source at once without hitting its ceiling. A single hung source holds at most a quarter of
     * the pool, which leaves 96 slots for every other source. Under more than 32 concurrent requests to
     * one source, the excess is reported {@code UNAVAILABLE}, which is the intended back-pressure.
     */
    static final int PER_SOURCE_SIZE = 32;

    /** What {@link #acquireAndStart} did. */
    enum Start { STARTED, SATURATED, START_FAILED }

    private static final Logger LOG = LoggerFactory.getLogger(SourceCallSlots.class);
    private static final AtomicLong THREAD_NUMBER = new AtomicLong();

    private final Semaphore pool;
    private final int perSourceSize;
    private final ConcurrentHashMap<String, Semaphore> sources = new ConcurrentHashMap<>();
    private final ThreadFactory threadFactory;
    private volatile Runnable afterCallHook = () -> { };

    SourceCallSlots() {
        this(POOL_SIZE, PER_SOURCE_SIZE);
    }

    SourceCallSlots(int poolSize, int perSourceSize) {
        this(poolSize, perSourceSize, SourceCallSlots::newThread);
    }

    SourceCallSlots(int poolSize, int perSourceSize, ThreadFactory threadFactory) {
        this.pool = new Semaphore(poolSize);
        this.perSourceSize = perSourceSize;
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    private static Thread newThread(Runnable task) {
        // Not inheriting inheritable thread-locals: the requesting thread's are not the call's.
        Thread thread = new Thread(null, task, "dataprism-source-" + THREAD_NUMBER.incrementAndGet(), 0, false);
        thread.setDaemon(true);
        // The constructor above resets it to the system loader; this runs on the call's virtual thread,
        // which inherited the requester's, and an adapter's ServiceLoader needs that one.
        thread.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        return thread;
    }

    /**
     * Takes the source's slot, then the pool's, and starts a thread running {@code task}. Both slots are
     * released when the thread's work ends, whatever it does, and only then is {@code afterRelease} run.
     *
     * @param created told of the thread before it is started
     * @return {@code SATURATED} when either slot was not free (nothing is held, nothing started);
     *         {@code START_FAILED} when the thread could not be created or started (the slots are given
     *         back at once); otherwise {@code STARTED}
     */
    Start acquireAndStart(String source, Runnable task, Runnable afterRelease, Consumer<Thread> created) {
        Semaphore own = sources.computeIfAbsent(source, k -> new Semaphore(perSourceSize));
        if (!own.tryAcquire()) {
            return Start.SATURATED;
        }
        if (!pool.tryAcquire()) {
            own.release();
            return Start.SATURATED;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                pool.release();
                own.release();
            }
        };
        Runnable wrapped = () -> {
            try {
                task.run();
            } finally {
                release.run();
                try {
                    afterCallHook.run();
                } finally {
                    afterRelease.run();
                }
            }
        };
        try {
            Thread thread = threadFactory.newThread(wrapped);
            created.accept(thread);
            thread.start();
        } catch (Throwable t) {
            release.run();
            // The class only: a message can carry a value.
            LOG.error("could not start a source call thread: {}", t.getClass().getSimpleName());
            return Start.START_FAILED;
        }
        return Start.STARTED;
    }

    /** Test hook: free permits in the pool. */
    int poolPermits() {
        return pool.availablePermits();
    }

    /** Test hook: free permits for one source. */
    int sourcePermits(String source) {
        Semaphore own = sources.get(source);
        return own == null ? perSourceSize : own.availablePermits();
    }

    /** Test hook: run on the call's thread as the last statement of its {@code finally}, after the slots are back. */
    void afterCallHook(Runnable hook) {
        this.afterCallHook = Objects.requireNonNull(hook, "hook");
    }
}
