package io.github.aindriub.dataprism.core.spi;

/** Advisory statement of how current a source's data is. */
public enum FreshnessHint {
    LIVE, NEAR_LIVE, HOURLY, DAILY, PERIODIC
}
