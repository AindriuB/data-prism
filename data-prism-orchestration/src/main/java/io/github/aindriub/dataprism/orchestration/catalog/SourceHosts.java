package io.github.aindriub.dataprism.orchestration.catalog;

import java.util.Map;
import java.util.Objects;

/**
 * The host of each configured source's base URL, by real source name. A module that configures sources from a
 * catalogue file publishes one so that the {@link SourceCatalog} can refuse a description that names a host; the
 * auto-configuration, which cannot see that module's types, reads this one.
 *
 * <p>Operator-side data: never put one in a response, a log line or an audit record.
 */
public record SourceHosts(Map<String, String> byName) {

    private static final SourceHosts NONE = new SourceHosts(Map.of());

    public SourceHosts {
        byName = Map.copyOf(Objects.requireNonNull(byName, "byName"));
    }

    /** No configured source has a host to check. */
    public static SourceHosts none() {
        return NONE;
    }

    @Override
    public String toString() {
        return "SourceHosts[" + byName.size() + " hosts]";
    }
}
