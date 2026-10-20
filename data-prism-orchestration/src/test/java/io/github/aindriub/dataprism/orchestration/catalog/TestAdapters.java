package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import java.util.List;
import java.util.Set;

/** Fixtures. Names are invented and neutral. */
final class TestAdapters {

    private TestAdapters() {
    }

    /** Compiled against the 0.7 SPI: declares no description(). */
    static class OldStyleAdapter implements DataSourceAdapter<String> {
        private final String name;

        OldStyleAdapter(String name) {
            this.name = name;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            throw new AssertionError("the catalog never fetches");
        }
    }

    static final class DescribedAdapter extends OldStyleAdapter {
        private final SourceDescription description;

        DescribedAdapter(String name, SourceDescription description) {
            super(name);
            this.description = description;
        }

        @Override
        public SourceDescription description() {
            return description;
        }
    }

    static final class WidgetLedgerAdapter extends OldStyleAdapter {
        private final SourceDescription description;

        WidgetLedgerAdapter(String name, SourceDescription description) {
            super(name);
            this.description = description;
        }

        @Override
        public SourceDescription description() {
            return description;
        }
    }

    static SourceDescription aliasText(String text) {
        return new SourceDescription(Set.of(), text, null, List.of(), null, null);
    }

    static SourceDescription texts(String alias, String real) {
        return new SourceDescription(Set.of(), alias, real, List.of(), null, null);
    }

    static SourceDescription types(Set<String> types, List<String> keys) {
        return new SourceDescription(types, null, null, keys.stream().map(KeyNamespace::new).toList(), null, null);
    }
}
