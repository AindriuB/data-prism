package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ServerJvmProbeTest {

    @Test
    void readsLiveThreadsAndHeapAfterGcFromTheMxBeans() {
        System.gc();

        ServerJvmProbe.Reading reading = ServerJvmProbe.read(ManagementFactory.getPlatformMBeanServer());

        assertThat(reading.liveThreads()).isPositive();
        assertThat(reading.heapAfterGcBytes()).isNotNull().isPositive();
    }

    @Test
    void aJvmItCannotAttachToReadsNothingRatherThanGuessing() {
        assertThat(ServerJvmProbe.forProcess(Long.MAX_VALUE).get()).isEqualTo(ServerJvmProbe.Reading.NONE);
    }
}
