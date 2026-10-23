package io.github.aindriub.dataprism.hazelcast.seal;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The one way a store writes a sealed value through an entry processor (docs/plan/design/cluster-security.md 2.3.1,
 * spike 219 answers 8 and 9).
 *
 * <p><b>Why a sealed write needs its own shape.</b> By default Hazelcast runs {@code process} again on the backup
 * replica, and a processor that seals inside {@code process} would draw a fresh nonce there: primary and backup would
 * hold different bytes. A retried invocation (the owner left, the call went to the new owner) runs {@code process}
 * again too. Both shapes below therefore define a backup processor that sets exactly the bytes the primary produced,
 * with the same time to live, and that never seals. A backup processor that only copied the value would leave the
 * backup without the time to live (answer 8), so it sets that itself.
 *
 * <ul>
 *   <li>{@link #compareAndSet}: the caller seals once, before the invocation. The processor compares the stored
 *       bytes with the expected bytes and sets the new bytes, which were sealed with a nonce used for nothing else.
 *       A stored value equal to the new bytes can therefore only have been written by this invocation (a retry after
 *       the first attempt applied), and the processor reports success without writing again.</li>
 *   <li>{@link #computing}: the next value depends on the stored one. The processor opens the stored value on the
 *       primary, applies a {@link Step} to the plaintext, seals the result once there and returns the resulting
 *       bytes. A retried invocation applies the step to whatever is stored then, so a step must be idempotent, or at
 *       worst over-count; it must never under-count.</li>
 * </ul>
 *
 * <p>The processors reach the member's {@link Sealer} through {@link #register}, which stores it in the instance's
 * member-local user context. That context is never replicated or serialised (answer 9). A processor that finds no
 * sealer refuses; it never writes an unsealed value.
 *
 * <p><b>Single key only.</b> A processor instance remembers what its run produced so that its backup processor can
 * carry exactly those bytes, so one instance serves one key. A second {@code process} call on the same instance
 * (as {@code executeOnKeys} and {@code executeOnEntries} make) throws {@link IllegalStateException}, which fails the
 * call loudly instead of giving one key's backup another key's bytes.
 *
 * <p>The caller holds the key's lock for a bounded time before invoking either shape (spike 219 answer 10: an
 * {@code executeOnKey} does not wait on a lock, but every other write to a locked key does), so a writer that cannot
 * lock refuses instead of racing.
 */
public final class SealedWrites {

    static final String SEALER_CONTEXT_KEY = "dataprism.cluster.sealer";
    static final String NAMES_CONTEXT_KEY = "dataprism.cluster.names";

    private static final String SINGLE_KEY = "a sealed-write processor serves one key";

    private SealedWrites() {
    }

    private static final String COUNT_CONTEXT_KEY = "dataprism.cluster.sealing.count";
    private static final Object REGISTRATION = new Object();

    /**
     * Makes the sealer and the keyed names reachable from processors and merge policies that run on this member.
     * Kept in the member's user context only: never replicated, never serialised.
     *
     * <p>Registration belongs to the instance and is reference-counted: the first call stores the sealer and names,
     * later calls on the same instance only count, and the sealer and names are removed by the {@link #unregister}
     * that balances the last {@code register}. A second wrapper therefore neither replaces nor removes the first
     * one's registration.
     */
    public static void register(HazelcastInstance instance, Sealer sealer, KeyedNames names) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(sealer, "sealer");
        Objects.requireNonNull(names, "names");
        synchronized (REGISTRATION) {
            var context = instance.getUserContext();
            int count = (Integer) context.getOrDefault(COUNT_CONTEXT_KEY, 0);
            if (count == 0) {
                context.put(SEALER_CONTEXT_KEY, sealer);
                context.put(NAMES_CONTEXT_KEY, names);
            }
            context.put(COUNT_CONTEXT_KEY, count + 1);
        }
    }

    /** Balances one {@link #register}; removes the registration with the last one. Safe on a gone instance. */
    public static void unregister(HazelcastInstance instance) {
        try {
            synchronized (REGISTRATION) {
                var context = instance.getUserContext();
                int count = (Integer) context.getOrDefault(COUNT_CONTEXT_KEY, 0);
                if (count <= 1) {
                    context.remove(COUNT_CONTEXT_KEY);
                    context.remove(SEALER_CONTEXT_KEY);
                    context.remove(NAMES_CONTEXT_KEY);
                } else {
                    context.put(COUNT_CONTEXT_KEY, count - 1);
                }
            }
        } catch (RuntimeException ignored) {
            // The instance may already be shut down.
        }
    }

    /** The sealer registered on {@code instance}, or null. */
    static Sealer sealerOf(HazelcastInstance instance) {
        return instance == null ? null : (Sealer) instance.getUserContext().get(SEALER_CONTEXT_KEY);
    }

    /** The keyed names registered on {@code instance}, or null. */
    public static KeyedNames namesOf(HazelcastInstance instance) {
        return instance == null ? null : (KeyedNames) instance.getUserContext().get(NAMES_CONTEXT_KEY);
    }

    /**
     * A compare-and-set of exact bytes. {@code expected} null means the key must be absent. {@code ttlMillis} of
     * zero or less leaves the map's default lifetime.
     */
    public static EntryProcessor<String, String, Boolean> compareAndSet(String expected, String next,
                                                                         long ttlMillis) {
        return new CompareAndSet(expected, Objects.requireNonNull(next, "next"), ttlMillis);
    }

    /** A write whose next value is computed from the stored plaintext on the primary and sealed once there. */
    public static EntryProcessor<String, String, Applied> computing(String map, Step step) {
        return new Computing(Objects.requireNonNull(map, "map"), Objects.requireNonNull(step, "step"));
    }

    /** What a {@link Step} decides, given the stored plaintext (null if there is no entry). */
    @FunctionalInterface
    public interface Step extends Serializable {
        Plan apply(byte[] current);
    }

    /** The decision of a {@link Step}: write a plaintext, delete the entry, or leave it, and what to return. */
    public static final class Plan {
        private enum Kind { WRITE, DELETE, UNCHANGED }

        private final Kind kind;
        private final byte[] next;
        private final long ttlMillis;
        private final Serializable result;

        private Plan(Kind kind, byte[] next, long ttlMillis, Serializable result) {
            this.kind = kind;
            this.next = next;
            this.ttlMillis = ttlMillis;
            this.result = result;
        }

        /** Seal {@code next} and store it, with the time to live (zero or less keeps the map's default). */
        public static Plan write(byte[] next, long ttlMillis, Serializable result) {
            return new Plan(Kind.WRITE, Objects.requireNonNull(next, "next").clone(), ttlMillis, result);
        }

        /** Remove the entry. */
        public static Plan delete(Serializable result) {
            return new Plan(Kind.DELETE, null, 0, result);
        }

        /** Leave the entry as it is. */
        public static Plan unchanged(Serializable result) {
            return new Plan(Kind.UNCHANGED, null, 0, result);
        }
    }

    /**
     * The answer of a computing processor: the bytes now stored (null if the entry was deleted or left alone) and the
     * step's own result.
     */
    public record Applied(String sealed, Serializable result) implements Serializable {
    }

    private static void set(Map.Entry<String, String> entry, String value, long ttlMillis) {
        if (ttlMillis > 0 && entry instanceof ExtendedMapEntry<String, String> extended) {
            extended.setValue(value, ttlMillis, TimeUnit.MILLISECONDS);
        } else {
            entry.setValue(value);
        }
    }

    private static final class CompareAndSet implements EntryProcessor<String, String, Boolean> {
        private final String expected;
        private final String next;
        private final long ttlMillis;
        /** Whether this run wrote on the primary; the backup is told to do the same, or nothing. */
        private transient boolean wrote;
        private transient boolean ran;

        private CompareAndSet(String expected, String next, long ttlMillis) {
            this.expected = expected;
            this.next = next;
            this.ttlMillis = ttlMillis;
        }

        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (ran) {
                throw new IllegalStateException(SINGLE_KEY);
            }
            ran = true;
            wrote = false;
            String stored = entry.getValue();
            if (next.equals(stored)) {
                // Sealed once with a nonce used for nothing else: only this invocation can have written these bytes.
                return true;
            }
            if (!Objects.equals(expected, stored)) {
                return false;
            }
            set(entry, next, ttlMillis);
            wrote = true;
            return true;
        }

        @Override
        public EntryProcessor<String, String, Boolean> getBackupProcessor() {
            // Hazelcast asks for a backup processor whether or not process changed the entry.
            return wrote ? new SetExact(next, ttlMillis) : new LeaveAlone<>();
        }
    }

    /** A backup processor for a run that changed nothing. */
    private record LeaveAlone<R>() implements EntryProcessor<String, String, R> {
        @Override
        public R process(Map.Entry<String, String> entry) {
            return null;
        }
    }

    /** The backup processor: sets exactly the primary's bytes and lifetime, or removes the entry. Never seals. */
    private record SetExact(String value, long ttlMillis) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (value == null) {
                entry.setValue(null);
            } else {
                set(entry, value, ttlMillis);
            }
            return true;
        }
    }

    private static final class Computing
            implements EntryProcessor<String, String, Applied>, HazelcastInstanceAware {

        private final String map;
        private final Step step;
        private transient Sealer sealer;
        private transient SetExact backup;
        private transient boolean ran;

        private Computing(String map, Step step) {
            this.map = map;
            this.step = step;
        }

        @Override
        public void setHazelcastInstance(HazelcastInstance instance) {
            this.sealer = sealerOf(instance);
        }

        @Override
        public Applied process(Map.Entry<String, String> entry) {
            if (ran) {
                throw new IllegalStateException(SINGLE_KEY);
            }
            ran = true;
            backup = null;
            if (sealer == null) {
                throw new IllegalStateException("no sealer is registered on this member");
            }
            byte[] current = null;
            String stored = entry.getValue();
            if (stored != null) {
                Opened opened = sealer.open(map, entry.getKey(), stored);
                if (opened.outcome() != Opened.Outcome.AUTHENTIC) {
                    // Fail closed: a value that does not open is neither read nor overwritten from here.
                    throw new IllegalStateException(opened.message());
                }
                current = opened.plaintext();
            }
            Plan plan = step.apply(current);
            switch (plan.kind) {
                case WRITE -> {
                    String sealed = sealer.seal(map, entry.getKey(), plan.next);
                    set(entry, sealed, plan.ttlMillis);
                    backup = new SetExact(sealed, plan.ttlMillis);
                    return new Applied(sealed, plan.result);
                }
                case DELETE -> {
                    entry.setValue(null);
                    backup = new SetExact(null, 0);
                    return new Applied(null, plan.result);
                }
                default -> {
                    return new Applied(null, plan.result);
                }
            }
        }

        @Override
        public EntryProcessor<String, String, Applied> getBackupProcessor() {
            SetExact carried = backup;
            // Hazelcast asks for a backup processor whether or not process changed the entry.
            return carried == null ? new LeaveAlone<>() : new BackupOf(carried);
        }
    }

    /** Adapts {@link SetExact} to the primary's result type; Hazelcast needs the same type parameters. */
    private record BackupOf(SetExact exact) implements EntryProcessor<String, String, Applied> {
        @Override
        public Applied process(Map.Entry<String, String> entry) {
            exact.process(entry);
            return null;
        }
    }
}
