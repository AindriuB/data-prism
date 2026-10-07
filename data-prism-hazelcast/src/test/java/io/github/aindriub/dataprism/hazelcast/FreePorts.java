package io.github.aindriub.dataprism.hazelcast;

import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Hands out loopback ports that no other build on this machine is given. Never derived from a clock.
 */
final class FreePorts {

    private FreePorts() {
    }

    /** The first of {@code count} consecutive ports, all claimed; the caller may bind them on 127.0.0.1. */
    static int consecutive(int count) {
        return claimPorts(count);
    }

    // Ports below the OS ephemeral range (no outgoing connection or port-0 bind takes one), probed on
    // loopback and wildcard, then claimed with a FileLock that every test JVM on this machine honours
    // and that is held until this JVM exits. A port is therefore never handed out twice, in this build
    // or in a concurrent one. At most three attempts, each on a failed probe or claim only.
    private static final Path PORT_CLAIMS = Path.of(System.getProperty("java.io.tmpdir"), "dataprism-test-ports");
    private static final List<FileChannel> CLAIMED = new ArrayList<>();
    private static final Random PORT_CHOICE = new Random();

    private static synchronized int claimPorts(int count) {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int base = 20000 + PORT_CHOICE.nextInt(12000);
            List<FileChannel> got = new ArrayList<>();
            try {
                Files.createDirectories(PORT_CLAIMS);
                for (int port = base; port < base + count; port++) {
                    try (ServerSocket loopback = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
                         ServerSocket wildcard = new ServerSocket(port, 1)) {
                        FileChannel channel = FileChannel.open(PORT_CLAIMS.resolve("port-" + port),
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        got.add(channel);
                        try {
                            if (channel.tryLock() == null) {
                                throw new IOException("port " + port + " claimed by another build");
                            }
                        } catch (OverlappingFileLockException claimedHere) {
                            throw new IOException("port " + port + " claimed in this JVM", claimedHere);
                        }
                    }
                }
                CLAIMED.addAll(got);
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
