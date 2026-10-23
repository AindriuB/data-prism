package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;

import java.util.Set;

/**
 * The boundary checks of one area of the cluster state (HazelcastStoredValueBoundaryTest). One implementation per
 * area owns the maps it names and nothing else, so a later task edits only the checker of the area it changes.
 */
public interface AreaBoundary {

    /** The full names of the maps this checker owns. */
    Set<String> maps();

    /**
     * Asserts the contents of one of {@link #maps()}: every key is the keyed name the test computes from the
     * canonical inputs it wrote, no key contains any of those inputs, and each value is the one those inputs imply.
     */
    void check(String map, IMap<Object, Object> contents, Inputs inputs);
}
