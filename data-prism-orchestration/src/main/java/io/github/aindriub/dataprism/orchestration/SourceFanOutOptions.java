package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;

import java.util.Objects;

/**
 * The optional collaborators of a {@link SourceFanOut}.
 *
 * @param metrics where per-source outcomes are counted; {@link PrivacyMetrics#none()} counts nothing
 * @param mdc     opened on each source's own thread from that source's request, so the call's
 *                validated external id is on every log line the fetch writes;
 *                {@link CorrelationMdc#off()} puts nothing anywhere
 */
public record SourceFanOutOptions(PrivacyMetrics metrics, CorrelationMdc mdc) {

    public SourceFanOutOptions {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(mdc, "mdc");
    }

    /** No metrics and no MDC. */
    public static SourceFanOutOptions defaults() {
        return new SourceFanOutOptions(PrivacyMetrics.none(), CorrelationMdc.off());
    }

    public SourceFanOutOptions withMetrics(PrivacyMetrics metrics) {
        return new SourceFanOutOptions(metrics, mdc);
    }

    public SourceFanOutOptions withMdc(CorrelationMdc mdc) {
        return new SourceFanOutOptions(metrics, mdc);
    }
}
