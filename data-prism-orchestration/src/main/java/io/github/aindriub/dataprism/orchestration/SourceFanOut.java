package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.RequestLimits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Calls the sources for one request, in parallel and under limits.
 *
 * <p>Virtual threads, because the work is blocking HTTP and nothing else: a
 * platform thread per source would cap the useful fan-out at whatever the pool
 * was sized to, for no reason other than the threading model.
 *
 * <p>Three things bound it, and they bound different failures. The bulkhead caps
 * concurrent calls, so a request over many sources cannot be the reason a source
 * system falls over. The per-source timeout caps how long any one source may
 * delay the answer. The source cap refuses the request outright rather than
 * trying and truncating.
 *
 * <p>A source that fails or times out is recorded as absent, not propagated. A
 * response built from four systems out of five is a real answer with a stated
 * gap; failing the whole request because one system is slow would make the
 * platform less available than the systems behind it.
 *
 * <p>The timeout is wall-clock from the start of the fan-out rather than from
 * when a source's own call begins. Under the bulkhead a queued source therefore
 * spends part of its budget waiting, which is intended: a caller waiting on the
 * platform cares how long the answer takes, not how the queue was arranged. It
 * does mean that setting {@code maxConcurrency} far below the source count will
 * show up as timeouts rather than as slow success, which is the right signal that
 * the request is too wide.
 */
public final class SourceFanOut {

    private final SourceCircuitBreaker breaker;
    private final Clock clock;

    public SourceFanOut(SourceCircuitBreaker breaker, Clock clock) {
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param requests the request to put to each source, keyed by source name.
     *                 A source absent from the map is not called at all
     * @return one entry per source, in the order the adapters were given, each
     *         carrying its outcome and its record where it answered
     */
    public List<Fetched> fetchAll(List<DataSourceAdapter<?>> adapters,
                                  Map<String, DataRequest> requests,
                                  RequestLimits limits) {
        List<DataSourceAdapter<?>> callable = new ArrayList<>();
        List<Fetched> results = new ArrayList<>();

        for (DataSourceAdapter<?> adapter : adapters) {
            String name = adapter.sourceName();
            if (!requests.containsKey(name)) {
                continue;
            }
            if (callable.size() >= limits.maxSources()) {
                // Refused rather than truncated: an answer silently built from
                // the first eight of twenty sources is worse than a refusal,
                // because nothing downstream can tell it happened.
                results.add(new Fetched(new SourceOutcome(name,
                        SourceOutcome.Status.SKIPPED_OVER_LIMIT, Duration.ZERO,
                        "request already at maxSources=" + limits.maxSources()), null));
                continue;
            }
            if (!breaker.allows(name)) {
                results.add(new Fetched(new SourceOutcome(name,
                        SourceOutcome.Status.CIRCUIT_OPEN, Duration.ZERO,
                        "breaker open after repeated failures"), null));
                continue;
            }
            callable.add(adapter);
        }

        Instant started = Instant.now(clock);
        Map<String, Future<Object>> futures = new LinkedHashMap<>();
        Semaphore bulkhead = new Semaphore(limits.maxConcurrency());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (DataSourceAdapter<?> adapter : callable) {
                DataRequest request = requests.get(adapter.sourceName());
                futures.put(adapter.sourceName(), executor.submit(() -> {
                    bulkhead.acquire();
                    try {
                        return adapter.fetch(request);
                    } finally {
                        bulkhead.release();
                    }
                }));
            }
            for (DataSourceAdapter<?> adapter : callable) {
                results.add(collect(adapter.sourceName(), futures.get(adapter.sourceName()),
                        started, limits.perSourceTimeout()));
            }
        }

        results.forEach(fetched -> breaker.record(fetched.outcome()));
        return List.copyOf(results);
    }

    private Fetched collect(String name, Future<Object> future, Instant started, Duration timeout) {
        long remaining = timeout.toMillis() - Duration.between(started, Instant.now(clock)).toMillis();
        try {
            Object record = future.get(Math.max(remaining, 0), TimeUnit.MILLISECONDS);
            Duration took = Duration.between(started, Instant.now(clock));
            return record == null
                    ? new Fetched(SourceOutcome.noData(name, took), null)
                    : new Fetched(SourceOutcome.answered(name, took), record);
        } catch (TimeoutException e) {
            // Interrupt the virtual thread so a slow source stops occupying its
            // bulkhead permit; without this the next request inherits the queue.
            future.cancel(true);
            return failed(name, started, SourceOutcome.Status.TIMED_OUT,
                    "no answer within " + timeout.toMillis() + "ms");
        } catch (ExecutionException e) {
            // The cause's message can carry a fragment of the record that caused
            // it, so only the exception type is recorded.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return failed(name, started, SourceOutcome.Status.FAILED,
                    cause.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failed(name, started, SourceOutcome.Status.FAILED, "interrupted");
        }
    }

    private Fetched failed(String name, Instant started, SourceOutcome.Status status, String detail) {
        return new Fetched(new SourceOutcome(name, status,
                Duration.between(started, Instant.now(clock)), detail), null);
    }

    /** One source's outcome, and its record where it answered. */
    public record Fetched(SourceOutcome outcome, Object record) {
    }
}
