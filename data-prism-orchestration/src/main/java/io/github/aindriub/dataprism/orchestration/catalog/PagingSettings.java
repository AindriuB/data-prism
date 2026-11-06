package io.github.aindriub.dataprism.orchestration.catalog;

import java.time.Duration;
import java.util.Objects;

/**
 * The paging settings of {@code list_sources} (batch-and-bulk-follow.md A.8). Binding them from
 * {@code dataprism.paging.*} is task 254's; this record validates the values it is given.
 *
 * @param defaultPageSize the page size when the call names none
 * @param maxPageSize     the ceiling for {@code pageSize}; a larger request gets the ceiling
 * @param lifetime        how long a page link is accepted, further capped by scope and token expiry
 */
public record PagingSettings(int defaultPageSize, int maxPageSize, Duration lifetime) {

    /** The message prefix of a refused setting. Messages name the property, never the value. */
    public static final String INVALID = "INVALID_PAGING_SETTING: ";

    public PagingSettings {
        Objects.requireNonNull(lifetime, "lifetime");
        if (defaultPageSize < 1) {
            throw new IllegalArgumentException(INVALID + "dataprism.paging.list-sources.page-size must be positive");
        }
        if (maxPageSize < 1 || maxPageSize > 0xFFFF) {
            throw new IllegalArgumentException(
                    INVALID + "dataprism.paging.list-sources.max-page-size must be 1 to 65535");
        }
        if (defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException(INVALID
                    + "dataprism.paging.list-sources.page-size must not exceed max-page-size");
        }
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException(INVALID + "dataprism.paging.lifetime must be positive");
        }
    }

    /** 25 entries, ceiling 100, fifteen minutes. */
    public static PagingSettings defaults() {
        return new PagingSettings(25, 100, Duration.ofMinutes(15));
    }

    /** The size that applies: the default when absent, otherwise the request capped at the ceiling. */
    public int effective(Integer requested) {
        return requested == null ? defaultPageSize : Math.min(requested, maxPageSize);
    }
}
