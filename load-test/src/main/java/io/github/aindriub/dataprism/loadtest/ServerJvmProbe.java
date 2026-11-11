package io.github.aindriub.dataprism.loadtest;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.function.Supplier;

/**
 * Reads the harness server's JVM for the interval rows: heap after the last collection and live threads. It reads
 * only the platform MXBeans, through the attach API, so the server needs no setting for it. A figure it cannot read
 * is null, never a guess; a target's members are not the driver's to read at all.
 */
final class ServerJvmProbe {

    /** What one reading found; null for a figure that could not be read. */
    record Reading(Long heapAfterGcBytes, Integer liveThreads) {
        static final Reading NONE = new Reading(null, null);
    }

    private ServerJvmProbe() { }

    /** A probe of the JVM with this process id; every failure to attach or read gives {@link Reading#NONE}. */
    static Supplier<Reading> forProcess(long pid) {
        return () -> {
            VirtualMachine vm = null;
            try {
                vm = VirtualMachine.attach(Long.toString(pid));
                try (JMXConnector connector = JMXConnectorFactory.connect(
                        new JMXServiceURL(vm.startLocalManagementAgent()))) {
                    return read(connector.getMBeanServerConnection());
                }
            } catch (Exception | LinkageError unreadable) {
                return Reading.NONE;
            } finally {
                if (vm != null) {
                    try {
                        vm.detach();
                    } catch (Exception ignored) {
                        // Detaching from a JVM that is gone.
                    }
                }
            }
        };
    }

    /** The reading of one MBean server: heap pools' usage after their last collection, and the thread count. */
    static Reading read(MBeanServerConnection connection) {
        Long heap = null;
        Integer threads = null;
        try {
            long sum = 0;
            boolean any = false;
            for (ObjectName pool : connection.queryNames(new ObjectName("java.lang:type=MemoryPool,*"), null)) {
                if (!"HEAP".equals(connection.getAttribute(pool, "Type"))) {
                    continue;
                }
                Object usage = connection.getAttribute(pool, "CollectionUsage");
                if (usage instanceof CompositeData data) {
                    sum += (Long) data.get("used");
                    any = true;
                }
            }
            heap = any ? sum : null;
        } catch (Exception unreadable) {
            heap = null;
        }
        try {
            threads = (Integer) connection.getAttribute(new ObjectName("java.lang:type=Threading"), "ThreadCount");
        } catch (Exception unreadable) {
            threads = null;
        }
        return new Reading(heap, threads);
    }
}
