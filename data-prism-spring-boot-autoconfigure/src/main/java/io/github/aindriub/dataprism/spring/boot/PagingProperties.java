package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/** {@code dataprism.paging.*}: how {@code list_sources} pages, and how long a page link lives. */
public class PagingProperties {
    private final ListSources listSources = new ListSources();
    /** How long a page link stays valid after it was issued. Positive. */
    private String lifetime = "15m";

    public ListSources getListSources() {
        return listSources;
    }

    public String getLifetime() {
        return lifetime;
    }

    /** Bound as text so that a value that is not a duration refuses with the stable code, not a binding error. */
    public void setLifetime(String v) {
        lifetime = v;
    }

    /** @throws DataPrismConfigurationException {@code INVALID_PAGING_SETTING} if the setting is not a duration */
    public Duration lifetimeValue() {
        try {
            return lifetime == null || lifetime.isBlank() ? null : DurationStyle.detectAndParse(lifetime.strip());
        } catch (IllegalArgumentException notADuration) {
            throw new DataPrismConfigurationException("INVALID_PAGING_SETTING",
                    "dataprism.paging.lifetime must be a positive duration");
        }
    }

    /** {@code dataprism.paging.list-sources.*}. */
    public static class ListSources {
        /** Entries on a page when the caller asks for no size. Positive and at most max-page-size. */
        private int pageSize = 25;
        /** The largest page a caller may ask for; a larger request is served at this size. 1 to 65535. */
        private int maxPageSize = 100;

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int v) {
            pageSize = v;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int v) {
            maxPageSize = v;
        }
    }
}
