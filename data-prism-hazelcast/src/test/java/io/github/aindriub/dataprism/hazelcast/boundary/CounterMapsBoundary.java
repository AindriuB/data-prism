package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The budget and caller-rate maps: keyed names, and values that are counts. */
public final class CounterMapsBoundary implements AreaBoundary {

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.BUDGET_MAP, PrivacyCluster.CALLER_RATE_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        if (map.equals(PrivacyCluster.BUDGET_MAP)) {
            // The scope total exists once a reservation has been made in the scope; reads alone leave only the
            // subject's counter. Its key is the keyed name under its own label, so no input appears in it.
            String total = in.names().scoped(PrivacyCluster.BUDGET_MAP + "#total", in.scopeId(), in.scopeId());
            if (contents.containsKey(total)) {
                in.assertKeys(map, contents, in.budgetKey(), total);
                assertThat(contents.get(total)).isInstanceOf(Integer.class);
            } else {
                in.assertKeys(map, contents, in.budgetKey());
            }
            assertThat(contents.get(in.budgetKey())).isInstanceOf(Integer.class);
        } else {
            in.assertKeys(map, contents, in.callerRateKey());
            assertThat(contents.get(in.callerRateKey()).toString()).matches("\\d+:\\d+");
        }
    }
}
