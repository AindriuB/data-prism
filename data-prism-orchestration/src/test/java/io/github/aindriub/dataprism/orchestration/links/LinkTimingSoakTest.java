package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.FollowRequest;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;

import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sealed-links.md section 7 obligation 10: an expired link, an authenticated link toward a target that is not
 * permitted and a forged token are not separable by the time {@code follow} takes to refuse them, beyond a stated
 * tolerance. A statistical soak, not a unit assertion, so it is tagged {@code timing}, runs only when
 * {@code -Ddataprism.timing=true} is set, and is excluded from {@code ./mvnw verify}. Task 263 runs it.
 *
 * <p>The tolerance is generous on purpose: it asks that the median of each class is within 50 percent of the
 * median of the others plus 200 microseconds, which separates a missing AEAD (an order of magnitude) from noise.
 */
@Tag("timing")
@EnabledIfSystemProperty(named = "dataprism.timing", matches = "true")
class LinkTimingSoakTest {

    private static final int WARMUP = 2_000;
    private static final int SAMPLES = 10_000;
    private static final double RELATIVE_TOLERANCE = 0.5;
    private static final long ABSOLUTE_TOLERANCE_NANOS = 200_000;

    private static long median(Supplier<String> link, FollowRig rig, DefaultContextOrchestrator orchestrator) {
        long[] nanos = new long[SAMPLES];
        for (int i = 0; i < WARMUP + SAMPLES; i++) {
            String token = link.get();
            long start = System.nanoTime();
            try {
                orchestrator.follow(FollowRequest.of(token), rig.context(), rig.caller());
            } catch (RuntimeException expected) {
                // every one of them is refused
            }
            long took = System.nanoTime() - start;
            if (i >= WARMUP) {
                nanos[i - WARMUP] = took;
            }
        }
        Arrays.sort(nanos);
        return nanos[SAMPLES / 2];
    }

    @Test
    void expiredNotPermittedAndForgedAreNotSeparable() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        // The limiter would lock the principal after a few forgeries: give it room.
        rig.limiter = new io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter(
                Integer.MAX_VALUE, Duration.ofMinutes(10), java.time.Clock.fixed(NOW, java.time.ZoneId.of("UTC")));
        DefaultContextOrchestrator orchestrator = rig.orchestrator();
        String expired = rig.direct("parcel-api", "parcel-ref", "P-1");
        String notPermitted = rig.direct("no-such-api", "parcel-ref", "P-1");
        char[] flipped = expired.toCharArray();
        flipped[flipped.length - 2] = flipped[flipped.length - 2] == 'A' ? 'B' : 'A';
        String forged = new String(flipped);

        rig.now = NOW.plus(Duration.ofHours(2));
        long expiredMedian = median(() -> expired, rig, orchestrator);
        rig.now = NOW;
        long notPermittedMedian = median(() -> notPermitted, rig, orchestrator);
        long forgedMedian = median(() -> forged, rig, orchestrator);

        long[] medians = {expiredMedian, notPermittedMedian, forgedMedian};
        long min = Arrays.stream(medians).min().orElseThrow();
        long max = Arrays.stream(medians).max().orElseThrow();
        assertThat(max - min).as("medians %s ns", Arrays.toString(medians))
                .isLessThanOrEqualTo((long) (min * RELATIVE_TOLERANCE) + ABSOLUTE_TOLERANCE_NANOS);
    }
}
