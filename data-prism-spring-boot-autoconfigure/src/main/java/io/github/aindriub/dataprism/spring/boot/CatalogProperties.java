package io.github.aindriub.dataprism.spring.boot;

import java.util.ArrayList;
import java.util.List;

/** {@code dataprism.catalog.*}: what a call that names no sources may reach, and which scopes may see real source names. */
public class CatalogProperties {
    /** The most permitted sources a call without targetSources may ask. Positive and at most dataprism.limits.max-sources. */
    private int unselectedSourceLimit = 8;
    /** Privacy scope ids that may be shown real source names to a caller holding EXPOSE_SOURCE_NAMES. An entry is an exact scope id or a prefix ending in one star. Empty by default: no scope shows real names. */
    private List<String> realNamesScopes = new ArrayList<>();

    public int getUnselectedSourceLimit() {
        return unselectedSourceLimit;
    }

    public void setUnselectedSourceLimit(int v) {
        unselectedSourceLimit = v;
    }

    public List<String> getRealNamesScopes() {
        return realNamesScopes;
    }

    public void setRealNamesScopes(List<String> v) {
        realNamesScopes = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }
}
