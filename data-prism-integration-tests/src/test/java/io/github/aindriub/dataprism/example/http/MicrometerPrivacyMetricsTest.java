package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link MicrometerPrivacyMetrics} over a bare {@link SimpleMeterRegistry}:
 * exactly {@link Metric}'s names appear, none of §89's banned words appear as
 * a tag, and nothing it does can throw back at a caller.
 */
class MicrometerPrivacyMetricsTest {

    /** §89 and this task's own list: never a metric label, key or value. */
    private static final Set<String> BANNED_TAGS = Set.of("idInternal", "name", "email", "PPSN", "accountId");

    @Test
    @DisplayName("after a run touching every Metric, the registry holds exactly Metric's names")
    void registryHoldsExactlyMetricNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrivacyMetrics metrics = new MicrometerPrivacyMetrics(registry);

        // One call per metric, all through the same untagged shape: a real
        // deployment never calls both increment(Metric) and increment(Metric,
        // String) for the same enum constant (see each Metric's call sites),
        // so mixing shapes here would register a Counter and then a Timer
        // under the same name and prove the guard rather than the name set.
        for (Metric metric : Metric.values()) {
            metrics.increment(metric);
        }

        Set<String> expected = Arrays.stream(Metric.values()).map(Metric::metricName)
                .collect(Collectors.toSet());
        Set<String> actual = registry.getMeters().stream().map(m -> m.getId().getName())
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(expected);
        assertThat(registry.getMeters()).hasSize(Metric.values().length);
    }

    @Test
    @DisplayName("no meter in the registry carries a banned tag key or value")
    void noMeterCarriesABannedTag() {
        SimpleMeterRegistry counters = new SimpleMeterRegistry();
        PrivacyMetrics counterMetrics = new MicrometerPrivacyMetrics(counters);
        for (Metric metric : Metric.values()) {
            counterMetrics.increment(metric, "customer-api");
        }

        SimpleMeterRegistry timers = new SimpleMeterRegistry();
        PrivacyMetrics timerMetrics = new MicrometerPrivacyMetrics(timers);
        timerMetrics.record(Metric.SOURCE_LATENCY, "account-api", Duration.ofMillis(1));

        for (Meter meter : Stream.concat(counters.getMeters().stream(), timers.getMeters().stream()).toList()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(BANNED_TAGS).doesNotContain(tag.getKey());
                assertThat(BANNED_TAGS).doesNotContain(tag.getValue());
            }
        }
    }

    @Test
    @DisplayName("a real conflicting registration is swallowed rather than failing the caller")
    void aConflictingRegistrationNeverFailsTheCaller() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Register the same name as a Timer first. Micrometer itself throws
        // IllegalArgumentException when a second, differently-typed meter is
        // then requested under that name — this is the real hazard
        // CachingSyntheticValueSource in data-prism-hazelcast already guards
        // against, reproduced here rather than assumed.
        registry.timer(Metric.MCP_REQUESTS.metricName());
        PrivacyMetrics metrics = new MicrometerPrivacyMetrics(registry);

        assertThatCode(() -> metrics.increment(Metric.MCP_REQUESTS)).doesNotThrowAnyException();
    }
}
