package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.KeyResolutionException;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Calls one resolver with a bounded wait and turns every contract violation into a closed reason.
 *
 * <p>Calls run on a dedicated, fixed-size pool of platform threads, never on the shared virtual-thread scheduler,
 * because a resolver that ignores interrupts would otherwise pin a carrier thread for ever. The caller waits at
 * most {@code timeout}, then interrupts the thread and reports {@code TIMEOUT}; a late result is never used. A
 * slot is held until the resolver thread actually returns, not until the timeout, so when every slot is taken a
 * new call fails at once with {@code UNAVAILABLE} without calling the resolver. Nothing here logs, calls
 * {@code name()}, or lets an exception, message or key out: a failure is a {@link ResolverOutcome.Failed} and one
 * metric.
 */
public final class ResolverInvoker {

    /** The longest key a resolver may return, in UTF-8 bytes. */
    static final int MAX_KEY_BYTES = 256;

    /** The default number of concurrent resolver calls. */
    public static final int DEFAULT_MAX_CONCURRENT_CALLS = 8;

    /** The one fixed latency tag: a metric carries no per-resolver label (OD-200-15). */
    private static final String LATENCY_TAG = "key-resolver";

    private final PrivacyMetrics metrics;
    private final ThreadPoolExecutor executor;
    private final Semaphore slots;

    public ResolverInvoker(PrivacyMetrics metrics) {
        this(metrics, DEFAULT_MAX_CONCURRENT_CALLS);
    }

    public ResolverInvoker(PrivacyMetrics metrics, int maxConcurrentCalls) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be at least 1");
        }
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(maxConcurrentCalls, maxConcurrentCalls, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(maxConcurrentCalls), runnable -> {
                    Thread thread = new Thread(runnable, "key-resolver-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.executor.allowCoreThreadTimeOut(true);
        this.slots = new Semaphore(maxConcurrentCalls);
    }

    public ResolverOutcome invoke(KeyResolver resolver, String key, ResolutionContext context, int maxResults,
                                  Duration timeout) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(timeout, "timeout");
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be at least 1");
        }
        long started = System.nanoTime();
        ResolverOutcome outcome = run(resolver, key, context, maxResults, timeout);
        if (outcome instanceof ResolverOutcome.Failed failed) {
            metrics.increment(metricFor(failed.reason()));
        }
        metrics.record(Metric.RESOLVER_LATENCY, LATENCY_TAG, Duration.ofNanos(System.nanoTime() - started));
        return outcome;
    }

    private ResolverOutcome run(KeyResolver resolver, String key, ResolutionContext context, int maxResults,
                                       Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.TIMEOUT);
        }
        if (!slots.tryAcquire()) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.UNAVAILABLE);
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        };
        // The slot is released as soon as the resolver code has returned, before the caller can see the result, so
        // back-to-back calls never see a slot still held by a finished call.
        FutureTask<ResolverOutcome> task = new FutureTask<>(() -> {
            try {
                return call(resolver, key, context, maxResults);
            } finally {
                release.run();
            }
        });
        try {
            // Also released here, for a task cancelled before it began (its callable then never runs).
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    release.run();
                }
            });
        } catch (RejectedExecutionException e) {
            release.run();
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.UNAVAILABLE);
        }
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.TIMEOUT);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.UNAVAILABLE);
        } catch (ExecutionException | RuntimeException e) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.UNAVAILABLE);
        }
    }

    /** Runs on the resolver thread; classifies everything so no exception crosses the thread boundary. */
    private static ResolverOutcome call(KeyResolver resolver, String key, ResolutionContext context, int maxResults) {
        try {
            return classify(resolver.resolve(key, context, maxResults), maxResults);
        } catch (KeyResolutionException e) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.valueOf(e.reason().name()));
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.UNAVAILABLE);
        }
    }

    private static ResolverOutcome classify(KeyResolver.Resolution resolution, int maxResults) {
        if (resolution == null) {
            return invalid();
        }
        List<String> keys = resolution.keys();
        if (keys.size() > maxResults) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.TOO_MANY);
        }
        if (keys.isEmpty()) {
            return resolution.more() ? invalid() : new ResolverOutcome.Empty();
        }
        for (String candidate : keys) {
            if (candidate == null || candidate.isBlank()
                    || candidate.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
                return invalid();
            }
        }
        return new ResolverOutcome.Keys(keys, resolution.more());
    }

    private static ResolverOutcome invalid() {
        return new ResolverOutcome.Failed(ResolverOutcome.Reason.INVALID_OUTPUT);
    }

    private static Metric metricFor(ResolverOutcome.Reason reason) {
        return switch (reason) {
            case UNAVAILABLE -> Metric.RESOLVER_UNAVAILABLE;
            case TIMEOUT -> Metric.RESOLVER_TIMEOUT;
            case REJECTED -> Metric.RESOLVER_REJECTED;
            case TOO_MANY -> Metric.RESOLVER_TOO_MANY;
            case INVALID_OUTPUT -> Metric.RESOLVER_INVALID_OUTPUT;
        };
    }
}
