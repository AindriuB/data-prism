package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;

import java.util.Set;

/** The member-card map. Nothing writes to it until task 227, so for now it must be empty. */
public final class MemberCardMapsBoundary implements AreaBoundary {

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.MEMBER_CARD_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        Inputs.assertEmpty(map, contents);
    }
}
