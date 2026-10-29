package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * {@code dataprism.limits.*}: six of the eight {@link RequestLimits} fields. Every default is the value of
 * the framework defaults; only a positive value is accepted, and a refusal names the property, never the value.
 *
 * <p>The other two are not bound yet (task 254). The overall request deadline is the framework default,
 * {@code PT10S}, or {@code per-source-timeout} where that is longer, because a deadline shorter than one call's
 * own timeout is refused; the deadline is raised at startup and logged once. The per-scope total of source reads
 * is the framework default. {@code per-source-timeout} is the time one call may take, measured from when that
 * call starts, not from the start of the request.
 */
public class LimitsProperties {
    private static final Logger LOG = LoggerFactory.getLogger(LimitsProperties.class);

    static final String CODE = "INVALID_REQUEST_LIMITS";

    /** Most sources one request may call. A request that selects more is refused, not silently cut short. */
    private int maxSources = 8;
    /** Most records one request may take from sources. */
    private int maxRecords = 500;
    /** Largest compact body returned to the client for one request, not one source's response. Must fit in an int number of bytes. */
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
                maxConcurrency, scopeReadBudget, requestTimeout(),
                RequestLimits.DEFAULT.scopeTotalReadBudget());
    }

    /**
     * The overall deadline, until it is bound from configuration (task 254): the default, or the
     * per-source timeout where that is longer, so a raised {@code per-source-timeout} still starts.
     * Said once, at INFO, when it is raised.
     */
    private Duration requestTimeout() {
        Duration floor = RequestLimits.DEFAULT.requestTimeout();
        if (perSourceTimeout.compareTo(floor) <= 0) {
            return floor;
        }
        LOG.info("dataprism.limits.per-source-timeout is above the default request deadline of {}; "
                + "the request deadline is raised to match it until it can be set", floor);
        return perSourceTimeout;
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
