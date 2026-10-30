package io.github.aindriub.dataprism.hazelcast;

import java.util.Random;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Hands out loopback ports that no other build on this machine is given. Never derived from a clock.
 */
public final class FreePorts {

    private FreePorts() {
    }

    /** The first of {@code count} consecutive ports, all claimed; the caller may bind them on 127.0.0.1. */
    public static int consecutive(int count) {
        return claimPorts(count);
    }

    // Ports below the OS ephemeral range (no outgoing connection or port-0 bind takes one), probed on
    // loopback and wildcard, then claimed with a FileLock that every test JVM on this machine honours
    // and that is held until this JVM exits. A port is therefore never handed out twice, in this build
    // or in a concurrent one. The run is drawn only among ports this JVM has not claimed (a second channel
    // on a file it holds would drop our own lock), and that never costs an attempt. At most three attempts,
    // each on a failed probe or a lock held by another JVM only.
    private static final Path PORT_CLAIMS = Path.of(System.getProperty("java.io.tmpdir"), "dataprism-test-ports");
    private static final List<FileChannel> CLAIMED = new ArrayList<>();
    private static final Set<Integer> CLAIMED_PORTS = new HashSet<>();
    private static final Random PORT_CHOICE = new Random();

    /**
     * The first of {@code count} consecutive ports inside {@code [low, low + range)} that this JVM has not claimed. A
     * random start, then a scan that wraps once round the range, so a run that overlaps this JVM's own claims is
     * skipped and never costs an attempt. Opens no socket. Throws when no unclaimed run is left.
     */
    static int pick(Set<Integer> claimed, int low, int range, int count, Random random) {
        int bases = range - count + 1;
        if (bases > 0) {
            int start = random.nextInt(bases);
            for (int i = 0; i < bases; i++) {
                int base = low + (start + i) % bases;
                boolean free = true;
                for (int port = base; port < base + count && free; port++) {
                    free = !claimed.contains(port);
                }
                if (free) {
                    return base;
                }
            }
        }
        throw new IllegalStateException("no unclaimed run of " + count + " ports left in this JVM");
    }

    private static synchronized int claimPorts(int count) {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int base = pick(CLAIMED_PORTS, 20000, 12000, count, PORT_CHOICE);
            List<FileChannel> got = new ArrayList<>();
            try {
                Files.createDirectories(PORT_CLAIMS);
                for (int port = base; port < base + count; port++) {
                    // One after the other: Linux refuses a wildcard bind while a loopback one is open. A
                    // foreign loopback listener fails the first probe; a foreign wildcard one (which on
                    // macOS the loopback probe cannot see) fails the second.
                    try (ServerSocket loopback = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                        // free on loopback
                    }
                    try (ServerSocket wildcard = new ServerSocket(port, 1)) {
                        // free on every address
                    }
                    FileChannel channel = FileChannel.open(PORT_CLAIMS.resolve("port-" + port),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    got.add(channel);
                    if (channel.tryLock() == null) {
                        throw new IOException("port " + port + " claimed by another build");
                    }
                }
                CLAIMED.addAll(got);
                for (int port = base; port < base + count; port++) {
                    CLAIMED_PORTS.add(port);
                }
                return base;
            } catch (IOException e) {
                last = e;
                for (FileChannel channel : got) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
        throw new IllegalStateException("no free loopback ports after 3 attempts", last);
    }
}
