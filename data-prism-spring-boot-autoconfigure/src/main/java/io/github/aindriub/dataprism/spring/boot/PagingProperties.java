package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;

/** {@code dataprism.paging.*}: how {@code list_sources} pages, and how long a page link lives. */
public class PagingProperties {
    private final ListSources listSources = new ListSources();
    /** How long a page link stays valid after it was issued. Positive. */
    private Duration lifetime = Duration.ofMinutes(15);

    public ListSources getListSources() {
        return listSources;
    }

    public Duration getLifetime() {
        return lifetime;
    }

    public void setLifetime(Duration v) {
        lifetime = v;
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
