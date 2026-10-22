package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.link.LinkTestSupport.MapKeys;
import io.github.aindriub.dataprism.core.link.LinkTestSupport.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static io.github.aindriub.dataprism.core.link.LinkTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryLinkAttemptLimiterTest {

    private TestClock clock;
    private InMemoryLinkAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        limiter = new InMemoryLinkAttemptLimiter(3, Duration.ofMinutes(10), clock);
    }

    @Test
    void locksAtTheLimitAndNotBefore() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        assertThat(limiter.locked("a")).isFalse();
        limiter.recordFailure("a");
        assertThat(limiter.locked("a")).isTrue();
    }

    @Test
    void otherCounterKeysAreUnaffected() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }
        assertThat(limiter.locked("b")).isFalse();
    }

    @Test
    void principalAndScopeAreSeparateCounters() {
        LinkKeys keys = new LinkKeys(new MapKeys().with("k1", material(1, 32)), "k1", "pseudo-key");
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(keys.attemptCounterKey("case-1", "alice"));
        }
        assertThat(limiter.locked(keys.attemptCounterKey("case-1", "alice"))).isTrue();
        assertThat(limiter.locked(keys.attemptCounterKey("case-1", "bob"))).isFalse();
        assertThat(limiter.locked(keys.attemptCounterKey("case-2", "alice"))).isFalse();
    }

    @Test
    void onlyCountedReasonsAreRecordedByACaller() {
        // The codec reports counted() and the caller records on it; this is the contract the flags carry.
        for (LinkReason reason : LinkReason.values()) {
            boolean counted = switch (reason) {
                case MALFORMED, TOO_LONG, VERSION, UNKNOWN_KEY, AUTH_FAILED -> true;
                default -> false;
            };
            assertThat(reason.counted()).as(reason.name()).isEqualTo(counted);
            assertThat(reason.auditValue()).isEqualTo("REFUSED:LINK_" + reason.name());
        }
    }

    @Test
    void incrementsDoNotExtendTheWindow() {
        limiter.recordFailure("a");
        clock.advance(Duration.ofMinutes(9));
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        assertThat(limiter.locked("a")).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.locked("a")).isFalse();
    }

    @Test
    void theWindowIsDecidedByTheClockAtTheBoundary() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }
        clock.set(T0.plus(Duration.ofMinutes(10)).minusMillis(1));
        assertThat(limiter.locked("a")).isTrue();
        clock.set(T0.plus(Duration.ofMinutes(10)));
        assertThat(limiter.locked("a")).isFalse();
    }

    @Test
    void aFailureAfterTheWindowStartsANewOne() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        clock.advance(Duration.ofMinutes(11));
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        assertThat(limiter.locked("a")).isFalse();
        limiter.recordFailure("a");
        assertThat(limiter.locked("a")).isTrue();
    }

    @Test
    void anEntryPastItsWindowIsDroppedOnNextAccessOrSweep() {
        limiter.recordFailure("a");
        limiter.recordFailure("b");
        assertThat(limiter.size()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.locked("a")).isFalse();
        assertThat(limiter.size()).isEqualTo(1);
        limiter.sweep();
        assertThat(limiter.size()).isZero();
    }

    @Test
    void memoryStaysBoundedUnderChurn() {
        for (int i = 0; i < 10_000; i++) {
            limiter.recordFailure("k" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        // Each entry lives 600 seconds; the periodic sweep keeps well under the total recorded.
        assertThat(limiter.size()).isLessThan(1200);
    }

    @Test
    void sixteenThreadsLoseNoCountAndLockExactlyAtTheLimit() throws Exception {
        int limit = 1000;
        InMemoryLinkAttemptLimiter big = new InMemoryLinkAttemptLimiter(limit, Duration.ofMinutes(10), clock);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        // 16 threads x 62 = 992 failures: below the limit, so none may be lost.
        for (int t = 0; t < 16; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 62; i++) {
                    big.recordFailure("shared");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        assertThat(big.locked("shared")).isFalse();
        for (int i = 0; i < 7; i++) {
            big.recordFailure("shared");
        }
        assertThat(big.locked("shared")).isFalse();
        big.recordFailure("shared");
        assertThat(big.locked("shared")).isTrue();
        pool.shutdownNow();
    }

    @Test
    void clusterWideIsFalseByDefault() {
        assertThat(limiter.clusterWide()).isFalse();
        LinkAttemptLimiter plain = new LinkAttemptLimiter() {
            @Override
            public boolean locked(String counterKey) {
                return false;
            }

            @Override
            public void recordFailure(String counterKey) {
            }
        };
        assertThat(plain.clusterWide()).isFalse();
    }

    @Test
    void constructionValidatesItsArguments() {
        assertThatThrownBy(() -> new InMemoryLinkAttemptLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryLinkAttemptLimiter(3, Duration.ofSeconds(Long.MAX_VALUE), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_LINK_ATTEMPT_WINDOW");
        new InMemoryLinkAttemptLimiter(3, Duration.ofDays(1), clock);
        assertThatThrownBy(() -> new InMemoryLinkAttemptLimiter(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLockedPrincipalIsRefusedEvenWithAValidLinkAtTheCallersGate() {
        // The caller checks locked() before open(); a locked key must not depend on the link presented.
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("pseudo-key", material(200, 32));
        LinkCodec codec = codec(provider, settings("k1", true, Duration.ofSeconds(30)), clock);
        LinkKeys keys = new LinkKeys(provider, "k1", "pseudo-key");
        LinkBinding binding = binding("case-1", "alice");
        String valid = codec.seal(follow(5), binding).orElseThrow().token();
        String counter = keys.attemptCounterKey("case-1", "alice");
        for (int i = 0; i < 3; i++) {
            var result = codec.open("!bad", binding, follows());
            if (result instanceof LinkOpenResult.Refused refused && refused.counted()) {
                limiter.recordFailure(counter);
            }
        }
        assertThat(limiter.locked(counter)).isTrue();
        assertThat(codec.open(valid, binding, follows())).isInstanceOf(LinkOpenResult.Opened.class);
        // The lock is the limiter's answer, independent of the link: it stays locked after a valid open.
        assertThat(limiter.locked(counter)).isTrue();
    }
}
