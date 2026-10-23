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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Calls one resolver with a bounded wait and turns every contract violation into a closed reason.
 *
 * <p>The call runs on its own virtual thread; the caller waits at most {@code timeout}, then interrupts the thread
 * and reports {@code TIMEOUT}. A late result is never used. Nothing here logs, and no exception, message or key
 * leaves this class: a failure is a {@link ResolverOutcome.Failed} and one metric.
 */
public final class ResolverInvoker {

    /** The longest key a resolver may return, in UTF-8 bytes. */
    static final int MAX_KEY_BYTES = 256;

    private static final Pattern TAG = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private final PrivacyMetrics metrics;

    public ResolverInvoker(PrivacyMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ResolverOutcome invoke(KeyResolver resolver, String key, ResolutionContext context, int maxResults,
                                  Duration timeout) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(timeout, "timeout");
        long started = System.nanoTime();
        ResolverOutcome outcome = run(resolver, key, context, maxResults, timeout);
        if (outcome instanceof ResolverOutcome.Failed failed) {
            metrics.increment(metricFor(failed.reason()));
        }
        metrics.record(Metric.RESOLVER_LATENCY, tag(resolver), Duration.ofNanos(System.nanoTime() - started));
        return outcome;
    }

    private static ResolverOutcome run(KeyResolver resolver, String key, ResolutionContext context, int maxResults,
                                       Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            return new ResolverOutcome.Failed(ResolverOutcome.Reason.TIMEOUT);
        }
        FutureTask<ResolverOutcome> task = new FutureTask<>(() -> call(resolver, key, context, maxResults));
        Thread.ofVirtual().name("key-resolver").start(task);
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

    /** The resolver's own name when it fits the grammar, so a hostile name cannot become a metric tag. */
    private static String tag(KeyResolver resolver) {
        String name;
        try {
            name = resolver.name();
        } catch (RuntimeException e) {
            return "unnamed";
        }
        return name != null && TAG.matcher(name).matches() ? name : "unnamed";
    }
}
