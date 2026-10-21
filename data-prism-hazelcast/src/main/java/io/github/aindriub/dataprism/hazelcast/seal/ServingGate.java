package io.github.aindriub.dataprism.hazelcast.seal;

/**
 * Whether this member may serve sealed entries. While it returns false, {@link Sealer#seal} and
 * {@link Sealer#open} refuse with {@code ClusterOperationRefusedException} (task 227 drives this).
 */
@FunctionalInterface
public interface ServingGate {

    boolean serving();

    /** The default: always serving. */
    static ServingGate open() {
        return () -> true;
    }
}
