package io.github.aindriub.dataprism.core.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.spi.MDCAdapter;

class CorrelationMdcTest {

    private static final String KEY = "transaction_id";

    private static final class MapAdapter implements MDCAdapter {
        final Map<String, String> map = new HashMap<>();
        int puts;
        int removes;

        @Override public void put(String key, String val) { puts++; map.put(key, val); }
        @Override public String get(String key) { return map.get(key); }
        @Override public void remove(String key) { removes++; map.remove(key); }
        @Override public void clear() { map.clear(); }
        @Override public Map<String, String> getCopyOfContextMap() { return new HashMap<>(map); }
        @Override public void setContextMap(Map<String, String> m) { map.clear(); map.putAll(m); }
        @Override public void pushByKey(String key, String value) { }
        @Override public String popByKey(String key) { return null; }
        @Override public java.util.Deque<String> getCopyOfDequeByKey(String key) { return null; }
        @Override public void clearDequeByKey(String key) { }
    }

    private static ExternalCorrelationId id(String value) {
        return CorrelationIdPolicy.opaque("[A-Za-z0-9-]{1,64}").validate(value).orElseThrow();
    }

    @Test
    @DisplayName("a present id is put under the key and removed on close")
    void putAndRemove() {
        MapAdapter adapter = new MapAdapter();
        CorrelationMdc mdc = CorrelationMdc.of(KEY, adapter);
        try (var scope = mdc.open(Optional.of(id("synthetic-clid-0001")))) {
            assertThat(adapter.map).containsOnly(Map.entry(KEY, "synthetic-clid-0001"));
        }
        assertThat(adapter.map).isEmpty();
    }

    @Test
    @DisplayName("a pre-existing value of the key is put back on close")
    void restoresPrevious() {
        MapAdapter adapter = new MapAdapter();
        adapter.map.put(KEY, "outer-value");
        CorrelationMdc mdc = CorrelationMdc.of(KEY, adapter);
        try (var scope = mdc.open(InboundCorrelation.present(id("synthetic-clid-0001")))) {
            assertThat(adapter.map.get(KEY)).isEqualTo("synthetic-clid-0001");
        }
        assertThat(adapter.map).containsOnly(Map.entry(KEY, "outer-value"));
    }

    @Test
    @DisplayName("an absent id, a rejected correlation and an off instance put nothing and remove nothing")
    void nothingToDo() {
        MapAdapter adapter = new MapAdapter();
        adapter.map.put(KEY, "outer-value");
        CorrelationMdc mdc = CorrelationMdc.of(KEY, adapter);
        try (var a = mdc.open(Optional.empty()); var b = mdc.open(InboundCorrelation.rejected());
                var c = mdc.open(InboundCorrelation.absent()); var d = CorrelationMdc.off().open(
                        InboundCorrelation.present(id("synthetic-clid-0001")))) {
            assertThat(adapter.map).containsOnly(Map.entry(KEY, "outer-value"));
        }
        assertThat(adapter.map).containsOnly(Map.entry(KEY, "outer-value"));
        assertThat(adapter.puts).isZero();
        assertThat(adapter.removes).isZero();
    }

    @Test
    @DisplayName("an invalid key cannot construct an instance")
    void invalidKey() {
        assertThatThrownBy(() -> CorrelationMdc.of("1bad", new MapAdapter()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every reserved name is reserved, in any case, and the accepted keys are not")
    void reserved() {
        for (String name : CorrelationMdc.RESERVED_NAMES) {
            assertThat(CorrelationMdc.isReserved(name)).as(name).isTrue();
            assertThat(CorrelationMdc.isReserved(name.toUpperCase())).as(name).isTrue();
        }
        for (String prefix : CorrelationMdc.RESERVED_PREFIXES) {
            assertThat(CorrelationMdc.isReserved(prefix + "x")).as(prefix).isTrue();
        }
        assertThat(List.of("transaction_id", "x_correlation_id"))
                .noneMatch(CorrelationMdc::isReserved)
                .allMatch(k -> CorrelationMdc.KEY_PATTERN.matcher(k).matches());
    }
}
