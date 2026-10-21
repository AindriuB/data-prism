package io.github.aindriub.dataprism.hazelcast.seal;

/**
 * Told of each entry that failed authentication. The argument is the map-name constant the caller passed to
 * {@link Sealer#open}; it never carries a key, a keyed name or entry data. A listener that throws is ignored.
 */
@FunctionalInterface
public interface TamperListener {

    void tamper(String map);

    static TamperListener none() {
        return map -> {};
    }
}
