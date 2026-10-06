package io.github.aindriub.dataprism.hazelcast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Finds loopback ports with a bind probe, retrying on a collision. Never derived from a clock. */
final class FreePorts {

    private static final int ATTEMPTS = 3;

    private FreePorts() {
    }

    /** The first of {@code count} consecutive free ports. */
    static int consecutive(int count) {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                int base = probe.getLocalPort();
                if (base + count - 1 > 65535) {
                    continue;
                }
                for (int i = 1; i < count; i++) {
                    try (ServerSocket next = new ServerSocket(base + i, 1, InetAddress.getLoopbackAddress())) {
                        // free
                    }
                }
                return base;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IllegalStateException("no free loopback ports after " + ATTEMPTS + " attempts", last);
    }
}
