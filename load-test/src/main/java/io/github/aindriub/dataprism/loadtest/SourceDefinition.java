package io.github.aindriub.dataprism.loadtest;

import java.util.SplittableRandom;

/**
 * One generated synthetic source: its name, the path the server is configured to call, and the latency
 * band it responds within.
 */
public record SourceDefinition(String name, String path, int latencyMinMs, int latencyMaxMs) {

    /** Deterministic latency for the {@code call}th request this source serves, uniform over its band. */
    public int sampleLatencyMs(long seed, long call) {
        if (latencyMaxMs <= latencyMinMs) {
            return latencyMinMs;
        }
        long mixed = seed * 0x9E3779B97F4A7C15L ^ name.hashCode() * 0xC2B2AE3D27D4EB4FL ^ call * 0x165667B19E3779F9L;
        return new SplittableRandom(mixed).nextInt(latencyMinMs, latencyMaxMs + 1);
    }
}
