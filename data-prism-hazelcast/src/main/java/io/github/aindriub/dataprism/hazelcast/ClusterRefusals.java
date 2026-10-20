package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the refusal for a cluster operation that failed. */
final class ClusterRefusals {

    private static volatile Logger log = LoggerFactory.getLogger(ClusterRefusals.class);

    private ClusterRefusals() {
    }

    static ClusterOperationRefusedException refused(Throwable cause) {
        return new ClusterOperationRefusedException(null);
    }

    /** Routes the log line to {@code replacement} until the returned handle is closed; for tests. */
    static AutoCloseable captureTo(Logger replacement) {
        Logger previous = log;
        log = replacement;
        return () -> log = previous;
    }
}
