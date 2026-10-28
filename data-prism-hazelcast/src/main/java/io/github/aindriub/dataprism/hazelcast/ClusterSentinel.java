package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.SealedWrites;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The sentinel of docs/plan/design/cluster-security.md 4.7: a sealed constant that the first member writes, under
 * the reserved key {@value #KEY} of the member-card map, and every later member reads. A member whose keys do not
 * open it cannot read the cluster, so it refuses to join ({@code CLUSTER_KEY_MISMATCH}) and never gets a card.
 *
 * <p>Which held key opens it decides the outcome (4.6): {@code current} is in step; {@code previous} means this
 * member is ahead and still joins; {@code next} means it is behind and still joins; none is a mismatch. This class
 * writes the sentinel only when it is absent, and only under {@code current}: a {@code next} key is never written,
 * and re-sealing the sentinel when the key advances is the rotation task's, not this one's.
 *
 * <p>It seals with a sealer that has no serving gate, because a closed gate must not stop a member proving itself.
 */
final class ClusterSentinel {

    /** The reserved map key. A keyed name is 43 characters, so no member card can have this key. */
    static final String KEY = "sentinel";

    private static final byte[] CONSTANT = "dp-sentinel".getBytes(StandardCharsets.UTF_8);

    /** What the sentinel in the cluster says about this member's keys. */
    enum Outcome {
        /** No sentinel is stored. */
        ABSENT,
        /** It opens under the member's {@code current} key. */
        CURRENT,
        /** It opens under the member's {@code previous} key: this member is ahead. */
        PREVIOUS,
        /** It opens under the member's {@code next} key: this member is behind. */
        NEXT,
        /** It opens under no held key, or is not a sentinel. */
        MISMATCH
    }

    private final PrivacyCluster cluster;
    private final ClusterKeys keys;
    private final Sealer sealer;
    private final Duration writeTimeout;
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /** How many sentinel writes were started, for tests. */
    int writesStarted() {
        return writes.get();
    }

    /** Whether a sentinel write has been started and has not completed at the owner, for tests. */
    boolean writeOutstanding() {
        return inFlight.get();
    }

    ClusterSentinel(PrivacyCluster cluster, ClusterKeys keys, Sealer sealer, Duration writeTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.sealer = Objects.requireNonNull(sealer, "sealer");
        this.writeTimeout = Objects.requireNonNull(writeTimeout, "writeTimeout");
    }

    /** Any value can be stored under a card key by a member without the key, so values are read as objects. */
    private IMap<String, Object> map() {
        return cluster.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
    }

    /**
     * Waits for a write at most {@code timeout}. A write that has to wait for a lock another member holds would
     * otherwise wait for as long as the lock is held. The wait is on a JDK future completed by a callback: waiting
     * on Hazelcast's own future on a ForkJoinPool worker would block through a ManagedBlocker with no bound. The
     * write itself is not cancelled; it stays queued at the owner until it can run.
     *
     * @throws IllegalStateException if the write did not finish in time, failed, or the thread was interrupted
     */
    static <T> T bounded(CompletionStage<T> write, Duration timeout) {
        CompletableFuture<T> future = new CompletableFuture<>();
        write.whenComplete((value, failure) -> {
            if (failure != null) {
                future.completeExceptionally(failure);
            } else {
                future.complete(value);
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            throw new IllegalStateException("a member card write did not finish in time");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a member card write was interrupted");
        } catch (ExecutionException failed) {
            throw new IllegalStateException("a member card write failed");
        }
    }

    /** Reads the sentinel and reports which held key opens it; writes nothing. */
    Outcome read() {
        return outcomeOf(map().get(KEY));
    }

    /**
     * Reads the sentinel and, if there is none, writes it under {@code current} with {@code putIfAbsent}; a
     * sentinel that another member wrote in the meantime is the one judged.
     */
    Outcome ensure() {
        IMap<String, Object> cards = map();
        Object stored = cards.get(KEY);
        if (stored == null) {
            String sealed = sealer.seal(PrivacyCluster.MEMBER_CARD_MAP, KEY, CONSTANT);
            // Writes only if the sentinel is still absent. The wait is bounded below: a processor on a locked key would
            // otherwise wait for the lock.
            IMap<String, String> typed = cluster.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
            // A write to a locked key stays queued at the owner after the caller stopped waiting: start no second one.
            if (!inFlight.compareAndSet(false, true)) {
                throw new IllegalStateException("a sentinel write is still outstanding");
            }
            CompletionStage<Boolean> write;
            try {
                write = typed.submitToKey(KEY, SealedWrites.compareAndSet(null, sealed, 0));
            } catch (RuntimeException notStarted) {
                inFlight.set(false);
                throw notStarted;
            }
            writes.incrementAndGet();
            write.whenComplete((done, failure) -> inFlight.set(false));
            if (Boolean.TRUE.equals(bounded(write, writeTimeout))) {
                return Outcome.CURRENT;
            }
            stored = cards.get(KEY);
        }
        return outcomeOf(stored);
    }

    private Outcome outcomeOf(Object stored) {
        if (stored == null) {
            return Outcome.ABSENT;
        }
        if (!(stored instanceof String envelope)) {
            return Outcome.MISMATCH;
        }
        Opened opened = sealer.open(PrivacyCluster.MEMBER_CARD_MAP, KEY, envelope);
        if (opened.outcome() != Opened.Outcome.AUTHENTIC
                || !MessageDigest.isEqual(CONSTANT, opened.plaintext())) {
            return Outcome.MISMATCH;
        }
        String id = opened.keyId();
        if (id.equals(keys.current().id())) {
            return Outcome.CURRENT;
        }
        if (keys.previous().map(ClusterKeys.Key::id).filter(id::equals).isPresent()) {
            return Outcome.PREVIOUS;
        }
        if (keys.next().map(ClusterKeys.Key::id).filter(id::equals).isPresent()) {
            return Outcome.NEXT;
        }
        return Outcome.MISMATCH;
    }
}
