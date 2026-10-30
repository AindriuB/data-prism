package io.github.aindriub.dataprism.hazelcast;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The port choice, with no socket opened: it depends only on the ports this JVM has claimed. */
class FreePortsTest {

    private static final int LOW = 20000;
    private static final int RANGE = 12000;

    @Test
    void withEveryPortClaimedButOneRunTheRunIsReturned() {
        Set<Integer> claimed = new HashSet<>();
        for (int port = LOW; port < LOW + RANGE; port++) {
            claimed.add(port);
        }
        for (int port = 27000; port < 27003; port++) {
            claimed.remove(port);
        }

        assertThat(FreePorts.pick(claimed, LOW, RANGE, 3, new Random(1))).isEqualTo(27000);
    }

    @Test
    void withNoRunLeftThePickerSaysNoUnclaimedRunIsLeftInThisJvm() {
        Set<Integer> claimed = new HashSet<>();
        for (int port = LOW; port < LOW + RANGE; port += 2) {
            claimed.add(port);
        }

        assertThatThrownBy(() -> FreePorts.pick(claimed, LOW, RANGE, 3, new Random(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no unclaimed run of 3 ports left in this JVM");
    }

    @Test
    void aThousandClaimedPortsNeverFailAPickOfFivePorts() {
        Set<Integer> claimed = claimedAtRandom(new Random(7), 1000);
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            int base = FreePorts.pick(claimed, LOW, RANGE, 5, random);
            for (int port = base; port < base + 5; port++) {
                assertThat(claimed).doesNotContain(port);
            }
            assertThat(base).isBetween(LOW, LOW + RANGE - 5);
        }
    }

    /** The logic this replaces fails the same picks: a random base, redrawn at most three times. */
    @Test
    void theRandomBaseWithThreeDrawsFailsTheSamePicks() {
        Set<Integer> claimed = claimedAtRandom(new Random(7), 1000);
        Random random = new Random(42);
        int failures = 0;
        for (int i = 0; i < 10_000; i++) {
            boolean found = false;
            for (int attempt = 0; attempt < 3 && !found; attempt++) {
                int base = LOW + random.nextInt(RANGE);
                found = true;
                for (int port = base; port < base + 5; port++) {
                    found &= !claimed.contains(port);
                }
            }
            if (!found) {
                failures++;
            }
        }
        assertThat(failures).isGreaterThanOrEqualTo(1);
    }

    private static Set<Integer> claimedAtRandom(Random random, int count) {
        Set<Integer> claimed = new HashSet<>();
        while (claimed.size() < count) {
            claimed.add(LOW + random.nextInt(RANGE));
        }
        return claimed;
    }
}
