package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * {@code dataprism.limits.*}: the six {@link RequestLimits} fields. Every default is the value of
 * the framework defaults; only a positive value is accepted, and a refusal names the property, never the value.
 */
public class LimitsProperties {
    static final String CODE = "INVALID_REQUEST_LIMITS";

    /** Most sources one request may call. A source beyond it is reported as skipped, not silently dropped. */
    private int maxSources = 8;
    /** Most records one request may take from sources. */
    private int maxRecords = 500;
    /** Largest response one request may take from a source. Must fit in an int number of bytes. */
    private DataSize maxResponseSize = DataSize.ofKilobytes(512);
    /** Time allowed to the sources of one request, measured from the start of the fan-out and not per call. */
    private Duration perSourceTimeout = Duration.ofSeconds(3);
    /** Most downstream calls in flight at once across one request. */
    private int maxConcurrency = 4;
    /** Reads of one subject allowed within one privacy scope before further reads are refused. */
    private int scopeReadBudget = 100;

    public int getMaxSources() {
        return maxSources;
    }

    public void setMaxSources(int v) {
        maxSources = v;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int v) {
        maxRecords = v;
    }

    public DataSize getMaxResponseSize() {
        return maxResponseSize;
    }

    public void setMaxResponseSize(DataSize v) {
        maxResponseSize = v;
    }

    public Duration getPerSourceTimeout() {
        return perSourceTimeout;
    }

    public void setPerSourceTimeout(Duration v) {
        perSourceTimeout = v;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int v) {
        maxConcurrency = v;
    }

    public int getScopeReadBudget() {
        return scopeReadBudget;
    }

    public void setScopeReadBudget(int v) {
        scopeReadBudget = v;
    }

    /**
     * The validated limits.
     *
     * @throws DataPrismConfigurationException {@code INVALID_REQUEST_LIMITS}, naming the first offending property only
     */
    RequestLimits toRequestLimits() {
        positive(maxSources, "max-sources");
        positive(maxRecords, "max-records");
        if (maxResponseSize == null || maxResponseSize.toBytes() <= 0 || maxResponseSize.toBytes() > Integer.MAX_VALUE) {
            throw refusal("max-response-size", "must be positive and fit in an int number of bytes");
        }
        if (perSourceTimeout == null || perSourceTimeout.isZero() || perSourceTimeout.isNegative()) {
            throw refusal("per-source-timeout", "must be positive");
        }
        positive(maxConcurrency, "max-concurrency");
        positive(scopeReadBudget, "scope-read-budget");
        return new RequestLimits(maxSources, maxRecords, (int) maxResponseSize.toBytes(), perSourceTimeout,
                maxConcurrency, scopeReadBudget);
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw refusal(name, "must be positive");
        }
    }

    private static DataPrismConfigurationException refusal(String name, String rule) {
        return new DataPrismConfigurationException(CODE, "dataprism.limits." + name + " " + rule);
    }
}
