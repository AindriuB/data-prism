package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pause map. The global and per-tool flags keep their plain keys until the pause document of task 228; a scope
 * pause is keyed by the scope's keyed name.
 */
public final class OversightMapsBoundary implements AreaBoundary {

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.OVERSIGHT_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        in.assertKeys(map, contents, "all", "tool:" + in.tool(), in.scopePauseKey());
        assertThat(contents.get("all")).isEqualTo(Boolean.TRUE);
        assertThat(contents.get("tool:" + in.tool())).isEqualTo(Boolean.TRUE);
        // Until 228 the value of a scope pause is the scope id, which is how the snapshot lists paused scopes.
        assertThat(contents.get(in.scopePauseKey())).isEqualTo(in.scopeId());
    }
}
