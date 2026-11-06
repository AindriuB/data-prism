package io.github.aindriub.dataprism.spring.boot;

import java.util.ArrayList;
import java.util.List;

/** {@code dataprism.catalog.*}: what a call that names no sources may reach, and which scopes may see real source names. */
public class CatalogProperties {
    /** The most permitted sources a call without targetSources may ask. Positive and at most dataprism.limits.max-sources. */
    private String unselectedSourceLimit = "8";
    /** Privacy scope ids that may be shown real source names to a caller holding EXPOSE_SOURCE_NAMES. An entry is an exact scope id or a prefix ending in one star. Empty by default: no scope shows real names. */
    private List<String> realNamesScopes = new ArrayList<>();

    public String getUnselectedSourceLimit() {
        return unselectedSourceLimit;
    }

    /** Bound as text so that a value that is not an integer refuses with the stable code, not a binding error. */
    public void setUnselectedSourceLimit(String v) {
        unselectedSourceLimit = v;
    }

    /** @throws DataPrismConfigurationException {@code INVALID_CATALOG_SETTING} if the setting is not an integer */
    public int unselectedSourceLimitValue() {
        try {
            return Integer.parseInt(unselectedSourceLimit == null ? "" : unselectedSourceLimit.strip());
        } catch (NumberFormatException notAnInteger) {
            throw new DataPrismConfigurationException("INVALID_CATALOG_SETTING",
                    "dataprism.catalog.unselected-source-limit must be a positive integer");
        }
    }

    public List<String> getRealNamesScopes() {
        return realNamesScopes;
    }

    public void setRealNamesScopes(List<String> v) {
        realNamesScopes = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }
}
