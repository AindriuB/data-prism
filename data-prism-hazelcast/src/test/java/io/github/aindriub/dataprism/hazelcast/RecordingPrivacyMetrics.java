package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link PrivacyMetrics} that remembers every call, so a test can inspect
 * what was recorded rather than just that nothing threw.
 *
 * <p>{@code sourceName} is captured verbatim on purpose: the whole point of the
 * tests that use this class is to prove that argument is never a scope id, a
 * subject id or a pseudonym, only a configured name such as a namespace.
 */
final class RecordingPrivacyMetrics implements PrivacyMetrics {

    record Increment(Metric metric, String sourceName) {
    }

    private final List<Increment> increments = new ArrayList<>();

    @Override
    public void increment(Metric metric) {
        increments.add(new Increment(metric, null));
    }

    @Override
    public void increment(Metric metric, String sourceName) {
        increments.add(new Increment(metric, sourceName));
    }

    @Override
    public void record(Metric metric, String sourceName, Duration duration) {
        increments.add(new Increment(metric, sourceName));
    }

    List<Increment> increments() {
        return List.copyOf(increments);
    }

    long countOf(Metric metric) {
        return increments.stream().filter(increment -> increment.metric() == metric).count();
    }
}
