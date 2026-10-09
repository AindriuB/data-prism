package io.github.aindriub.dataprism.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Delivers logged audit events to {@link AuditEventListener}s on one daemon dispatcher thread behind a
 * bounded queue (owner decision D-163-A).
 *
 * <p>{@link AuditRecorder} hands an event over with {@link #publish}, a single non-blocking {@code offer}
 * made after the authoritative write returned. A full queue drops the event for listeners only and counts
 * it; the count is logged, rate limited, as {@code AUDIT_LISTENER_DROPPED} (code and counts, never event
 * content). Order is the recorder's sequence order, because publication happens inside the recorder's
 * lock and one thread consumes. With no listeners no thread is started.
 *
 * <p>{@link #close()} drains what is queued for at most the drain timeout, then abandons the rest and
 * logs the count as dropped. A listener that ignores interruption can outlive the drain; the thread is a
 * daemon, so it never keeps the JVM alive.
 */
public final class AuditEventListeners implements AutoCloseable {

    /** Default time {@link #close()} waits for queued events to be delivered. */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    /** Smallest gap between two {@code AUDIT_LISTENER_DROPPED} lines while drops continue. */
    public static final Duration DEFAULT_DROP_LOG_INTERVAL = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(AuditEventListeners.class);
    /** Queued after the last event so the dispatcher stops once it has delivered everything before it. */
    private static final Object STOP = new Object();

    private final List<AuditEventListener> listeners;
    private final int capacity;
    private final Duration drainTimeout;
    private final long dropLogIntervalNanos;
    private final LongSupplier nanoTime;
    private final Logger log;
    private final BlockingQueue<Object> queue;
    private final Thread thread;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedUnreported = new AtomicLong();
    private final AtomicLong lastDropLogNanos;
    private final AtomicLong firstDropLogged = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean abandoned;

    public AuditEventListeners(List<AuditEventListener> listeners, int queueCapacity) {
        this(listeners, queueCapacity, DEFAULT_DRAIN_TIMEOUT, DEFAULT_DROP_LOG_INTERVAL, System::nanoTime, LOG);
    }

    AuditEventListeners(List<AuditEventListener> listeners, int queueCapacity, Duration drainTimeout,
                        Duration dropLogInterval, LongSupplier nanoTime, Logger log) {
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.capacity = queueCapacity;
        this.drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
        this.dropLogIntervalNanos = dropLogInterval.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.log = Objects.requireNonNull(log, "log");
        this.lastDropLogNanos = new AtomicLong(nanoTime.getAsLong());
        // One slot beyond the capacity is reserved for STOP, so close() never competes with events.
        this.queue = new ArrayBlockingQueue<>(queueCapacity + 1);
        if (this.listeners.isEmpty()) {
            this.thread = null;
        } else {
            this.thread = new Thread(this::run, "data-prism-audit-listeners");
            this.thread.setDaemon(true);
            this.thread.start();
        }
    }

    /** True when at least one listener is registered. */
    public boolean active() {
        return thread != null;
    }

    /**
     * Total events not delivered to listeners because the queue was full or the dispatcher was closed.
     */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * O(1) and non-blocking; never throws. Package-private: only the recorder, after a successful
     * write, hands events over.
     */
    void publish(AuditEvent event) {
        if (thread == null) {
            return;
        }
        if (closed || queue.size() >= capacity || !queue.offer(event)) {
            noteDrop(1);
        }
    }

    private void noteDrop(long count) {
        dropped.addAndGet(count);
        droppedUnreported.addAndGet(count);
        long now = nanoTime.getAsLong();
        long last = lastDropLogNanos.get();
        boolean first = firstDropLogged.compareAndSet(0, 1);
        if ((first || now - last >= dropLogIntervalNanos) && lastDropLogNanos.compareAndSet(last, now)) {
            reportDrops();
        }
    }

    private void reportDrops() {
        long since = droppedUnreported.getAndSet(0);
        if (since > 0) {
            log.warn("AUDIT_LISTENER_DROPPED: " + since + " audit events not delivered to listeners since the"
                    + " last report (" + dropped.get() + " in total; queue capacity " + capacity
                    + "); the audit log is unaffected");
        }
    }

    private void run() {
        try {
            while (!abandoned) {
                Object next = queue.take();
                if (next == STOP) {
                    return;
                }
                deliver((AuditEvent) next);
            }
        } catch (InterruptedException e) {
            // close() abandons a dispatcher that outlived the drain timeout; the thread ends here.
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(AuditEvent event) {
        for (AuditEventListener listener : listeners) {
            if (abandoned) {
                return;
            }
            try {
                listener.onAuditEvent(event);
            } catch (Throwable t) {
                // Isolation is the contract. Only classes and identifiers are logged: a listener's exception
                // message or stack can quote the event or its destination (D-163-D).
                log.warn("AUDIT_LISTENER_FAILED: listener=" + listener.getClass().getName()
                        + " eventId=" + event.eventId() + " sequence=" + event.sequence()
                        + " exception=" + t.getClass().getName());
            }
        }
    }

    /**
     * Stops accepting events, delivers what is queued for up to the drain timeout, then abandons the rest
     * (counted and logged as dropped) and interrupts the dispatcher. Idempotent.
     */
    @Override
    public void close() {
        if (thread == null || closed) {
            closed = true;
            return;
        }
        closed = true;
        queue.offer(STOP); // the reserved slot; cannot fail unless publish raced past the closed check
        try {
            thread.join(drainTimeout.toMillis());
            if (thread.isAlive()) {
                abandoned = true;
                thread.interrupt();
                thread.join(1000);
            }
        } catch (InterruptedException e) {
            abandoned = true;
            thread.interrupt();
            Thread.currentThread().interrupt();
        }
        long left = 0;
        for (Object o : queue) {
            if (o != STOP) {
                left++;
            }
        }
        queue.clear();
        if (left > 0) {
            dropped.addAndGet(left);
            droppedUnreported.addAndGet(left);
        }
        reportDrops();
    }
}
