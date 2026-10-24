package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.splitbrainprotection.SplitBrainProtectionException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the refusal for a cluster operation that failed, and logs it once.
 *
 * <p>The log line carries the code and the cause's simple class name, and nothing else from the cause: its message can
 * echo a key, and its stack trace would repeat it. The cause itself is chained on the exception, for a debugger.
 */
final class ClusterRefusals {

    private static volatile Logger log = LoggerFactory.getLogger(ClusterRefusals.class);

    private ClusterRefusals() {
    }

    /** The refusal to throw for {@code cause}, after one WARN line. Call it only where the refusal is first made. */
    static ClusterOperationRefusedException refused(Throwable cause) {
        log.warn("{}: a cluster operation was refused; cause {}", ClusterOperationRefusedException.CODE,
                cause == null ? "none" : cause.getClass().getSimpleName());
        return new ClusterOperationRefusedException(cause);
    }

    /**
     * What to throw for a failed cluster call: a Data Prism refusal and a lost write quorum keep their own
     * exceptions, and anything else becomes {@link #refused(Throwable)}. The one place that decides which failures
     * count as cluster failures, so every caller maps the same set.
     */
    static RuntimeException mapped(RuntimeException failure) {
        if (failure instanceof PrivacyRefusedException || failure instanceof SplitBrainProtectionException) {
            return failure;
        }
        return refused(failure);
    }

    /** Routes the log line to {@code replacement} until the returned handle is closed; for tests. */
    static AutoCloseable captureTo(Logger replacement) {
        Logger previous = log;
        log = replacement;
        return () -> log = previous;
    }
}
