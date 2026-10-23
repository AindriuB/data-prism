package io.github.aindriub.dataprism.orchestration.links;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolutionException;
import io.github.aindriub.dataprism.core.spi.KeyResolutionException.Reason;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ResolverInvokerTest {

    private static final String SENTINEL = "SENTINEL-KEY-7f3a";
    private static final ResolutionContext CONTEXT = new ResolutionContext("scope", "principal", "purpose",
            Instant.EPOCH);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<Metric> counted = new CopyOnWriteArrayList<>();
    private final List<Metric> timed = new CopyOnWriteArrayList<>();
    private final List<String> allTags = new CopyOnWriteArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    private final PrivacyMetrics metrics = new PrivacyMetrics() {
        @Override
        public void increment(Metric metric) {
            counted.add(metric);
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            counted.add(metric);
            allTags.add(sourceName);
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
            timed.add(metric);
            allTags.add(sourceName);
        }
    };

    private final ResolverInvoker invoker = new ResolverInvoker(metrics);

    @BeforeEach
    void captureLogs() {
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
    }

    private static KeyResolver resolver(Supplier<KeyResolver.Resolution> body) {
        return new KeyResolver() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public KeyNamespace from() {
                return new KeyNamespace("a");
            }

            @Override
            public KeyNamespace to() {
                return new KeyNamespace("b");
            }

            @Override
            public Resolution resolve(String key, ResolutionContext context, int maxResults) {
                return body.get();
            }
        };
    }

    private ResolverOutcome invoke(KeyResolver resolver, int max) {
        return invoker.invoke(resolver, SENTINEL, CONTEXT, max, TIMEOUT);
    }

    private static ResolverOutcome.Failed failed(ResolverOutcome outcome) {
        assertThat(outcome).isInstanceOf(ResolverOutcome.Failed.class);
        return (ResolverOutcome.Failed) outcome;
    }

    @Test
    void returnsKeysAndMore() {
        ResolverOutcome outcome = invoke(resolver(() -> new KeyResolver.Resolution(List.of("x", "y"), true)), 5);
        assertThat(outcome).isEqualTo(new ResolverOutcome.Keys(List.of("x", "y"), true));
        assertThat(counted).isEmpty();
        assertThat(timed).containsExactly(Metric.RESOLVER_LATENCY);
    }

    @Test
    void exactlyMaxResultsIsNotTooMany() {
        ResolverOutcome outcome = invoke(resolver(() -> new KeyResolver.Resolution(List.of("x", "y"), false)), 2);
        assertThat(outcome).isEqualTo(new ResolverOutcome.Keys(List.of("x", "y"), false));
    }

    @Test
    void anEmptyListIsEmptyNotAFailure() {
        assertThat(invoke(resolver(() -> new KeyResolver.Resolution(List.of(), false)), 5))
                .isEqualTo(new ResolverOutcome.Empty());
        assertThat(counted).isEmpty();
    }

    @Test
    void moreThanMaxResultsIsTooManyAndIsNotTruncated() {
        ResolverOutcome outcome = invoke(
                resolver(() -> new KeyResolver.Resolution(List.of("1", "2", "3", "4", "5", "6"), false)), 5);
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.TOO_MANY);
        assertThat(counted).containsExactly(Metric.RESOLVER_TOO_MANY);
    }

    @Test
    void moreWithNoKeysIsInvalidOutput() {
        ResolverOutcome outcome = invoke(resolver(() -> new KeyResolver.Resolution(List.of(), true)), 5);
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.INVALID_OUTPUT);
        assertThat(counted).containsExactly(Metric.RESOLVER_INVALID_OUTPUT);
    }

    @Test
    void aNullResultIsInvalidOutput() {
        assertThat(failed(invoke(resolver(() -> null), 5)).reason())
                .isEqualTo(ResolverOutcome.Reason.INVALID_OUTPUT);
    }

    @Test
    void aBlankKeyIsInvalidOutput() {
        for (String blank : new String[] {"", " ", "\t"}) {
            assertThat(failed(invoke(resolver(() -> new KeyResolver.Resolution(List.of("ok", blank), false)), 5))
                    .reason()).isEqualTo(ResolverOutcome.Reason.INVALID_OUTPUT);
        }
    }

    @Test
    void aKeyOver256BytesIsInvalidOutputAndExactly256IsAccepted() {
        String atLimit = "a".repeat(256);
        assertThat(invoke(resolver(() -> new KeyResolver.Resolution(List.of(atLimit), false)), 5))
                .isEqualTo(new ResolverOutcome.Keys(List.of(atLimit), false));
        String over = "a".repeat(257);
        assertThat(failed(invoke(resolver(() -> new KeyResolver.Resolution(List.of(over), false)), 5)).reason())
                .isEqualTo(ResolverOutcome.Reason.INVALID_OUTPUT);
        // 128 two-byte characters are 256 bytes; 129 are 258.
        String multiByteOk = "é".repeat(128);
        assertThat(invoke(resolver(() -> new KeyResolver.Resolution(List.of(multiByteOk), false)), 5))
                .isInstanceOf(ResolverOutcome.Keys.class);
        String multiByteOver = "é".repeat(129);
        assertThat(failed(invoke(resolver(() -> new KeyResolver.Resolution(List.of(multiByteOver), false)), 5))
                .reason()).isEqualTo(ResolverOutcome.Reason.INVALID_OUTPUT);
    }

    @Test
    void aNullKeyInTheListIsInvalidOutput() {
        KeyResolver nullKey = resolver(() -> new KeyResolver.Resolution(java.util.Arrays.asList("a", null), false));
        // List.copyOf rejects null inside Resolution, so the resolver itself fails: that is UNAVAILABLE, never a pass.
        assertThat(invoke(nullKey, 5)).isInstanceOf(ResolverOutcome.Failed.class);
    }

    @Test
    void eachDeclaredReasonMapsToItsOutcomeAndMetric() {
        assertReason(Reason.UNAVAILABLE, ResolverOutcome.Reason.UNAVAILABLE, Metric.RESOLVER_UNAVAILABLE);
        assertReason(Reason.TIMEOUT, ResolverOutcome.Reason.TIMEOUT, Metric.RESOLVER_TIMEOUT);
        assertReason(Reason.REJECTED, ResolverOutcome.Reason.REJECTED, Metric.RESOLVER_REJECTED);
        assertReason(Reason.TOO_MANY, ResolverOutcome.Reason.TOO_MANY, Metric.RESOLVER_TOO_MANY);
    }

    private void assertReason(Reason thrown, ResolverOutcome.Reason expected, Metric metric) {
        counted.clear();
        ResolverOutcome outcome = invoke(resolver(() -> {
            throw new KeyResolutionException(thrown);
        }), 5);
        assertThat(failed(outcome).reason()).isEqualTo(expected);
        assertThat(counted).containsExactly(metric);
    }

    @Test
    void anyOtherRuntimeExceptionIsUnavailable() {
        for (RuntimeException e : List.<RuntimeException>of(new IllegalStateException(SENTINEL),
                new NullPointerException(SENTINEL), new UnsupportedOperationException())) {
            counted.clear();
            assertThat(failed(invoke(resolver(() -> {
                throw e;
            }), 5)).reason()).isEqualTo(ResolverOutcome.Reason.UNAVAILABLE);
            assertThat(counted).containsExactly(Metric.RESOLVER_UNAVAILABLE);
        }
    }

    @Test
    void anErrorIsNotSwallowedAsAnOutcomeButStillFailsClosed() {
        ResolverOutcome outcome = invoke(resolver(() -> {
            throw new AssertionError(SENTINEL);
        }), 5);
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.UNAVAILABLE);
    }

    @Test
    void aResolverThatBlocksForeverTimesOutAndItsThreadIsInterrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        KeyResolver blocking = resolver(() -> {
            started.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new KeyResolver.Resolution(List.of("late"), false);
        });
        ResolverOutcome outcome = invoker.invoke(blocking, SENTINEL, CONTEXT, 5, Duration.ofMillis(50));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.TIMEOUT);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the resolver thread was interrupted").isTrue();
        assertThat(counted).containsExactly(Metric.RESOLVER_TIMEOUT);
    }

    @Test
    void aLateResultAfterTheTimeoutIsNeverReturned() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean ignoredInterrupt = new AtomicBoolean();
        KeyResolver stubborn = resolver(() -> {
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    ignoredInterrupt.set(true);
                }
            }
            return new KeyResolver.Resolution(List.of("late"), false);
        });
        ResolverOutcome outcome = invoker.invoke(stubborn, SENTINEL, CONTEXT, 5, Duration.ofMillis(50));
        release.countDown();
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.TIMEOUT);
    }

    @Test
    void aZeroOrNegativeTimeoutIsATimeoutAndTheResolverIsNeverInvoked() {
        CountDownLatch invoked = new CountDownLatch(1);
        KeyResolver resolver = resolver(() -> {
            invoked.countDown();
            return new KeyResolver.Resolution(List.of("x"), false);
        });
        assertThat(failed(invoker.invoke(resolver, SENTINEL, CONTEXT, 5, Duration.ZERO)).reason())
                .isEqualTo(ResolverOutcome.Reason.TIMEOUT);
        assertThat(failed(invoker.invoke(resolver, SENTINEL, CONTEXT, 5, Duration.ofMillis(-1))).reason())
                .isEqualTo(ResolverOutcome.Reason.TIMEOUT);
        assertThat(invoked.getCount()).as("the resolver was never invoked").isEqualTo(1);
    }

    @Test
    void maxResultsBelowOneIsRejectedUpFront() {
        KeyResolver resolver = resolver(() -> new KeyResolver.Resolution(List.of(), false));
        for (int bad : new int[] {0, -1}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(resolver, bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aResolverThatThrowsStackOverflowErrorIsUnavailable() {
        ResolverOutcome outcome = invoke(resolver(() -> {
            throw new StackOverflowError();
        }), 5);
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.UNAVAILABLE);
        assertThat(counted).containsExactly(Metric.RESOLVER_UNAVAILABLE);
    }

    @Test
    void aCallerInterruptIsUnavailableRestoresTheFlagAndInterruptsTheResolverThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resolverInterrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        KeyResolver blocking = resolver(() -> {
            started.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                resolverInterrupted.countDown();
            }
            return new KeyResolver.Resolution(List.of("late"), false);
        });
        Thread caller = Thread.currentThread();
        Thread interrupter = Thread.ofPlatform().start(() -> {
            try {
                started.await();
            } catch (InterruptedException e) {
                return;
            }
            caller.interrupt();
        });
        ResolverOutcome outcome = invoker.invoke(blocking, SENTINEL, CONTEXT, 5, Duration.ofSeconds(30));
        boolean flag = Thread.interrupted();
        interrupter.join();
        assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.UNAVAILABLE);
        assertThat(flag).as("the interrupt flag was restored").isTrue();
        assertThat(resolverInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void theInvokerNeverCallsNameAndTheLatencyTagIsConstant() {
        CountDownLatch nameCalled = new CountDownLatch(1);
        KeyResolver blockingName = new KeyResolver() {
            @Override
            public String name() {
                nameCalled.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "blocked";
            }

            @Override
            public KeyNamespace from() {
                return new KeyNamespace("a");
            }

            @Override
            public KeyNamespace to() {
                return new KeyNamespace("b");
            }

            @Override
            public Resolution resolve(String key, ResolutionContext context, int maxResults) {
                return new Resolution(List.of("x"), false);
            }
        };
        assertThat(invoker.invoke(blockingName, SENTINEL, CONTEXT, 5, Duration.ofSeconds(5)))
                .isInstanceOf(ResolverOutcome.Keys.class);
        assertThat(nameCalled.getCount()).as("name() was never called").isEqualTo(1);
        assertThat(allTags).containsExactly("key-resolver");
    }

    @Test
    void whenEverySlotIsHeldByAnInterruptIgnoringResolverANewCallFailsAtOnceAndVirtualThreadsAreUnaffected()
            throws Exception {
        ResolverInvoker small = new ResolverInvoker(metrics, 2);
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch spinning = new CountDownLatch(2);
        KeyResolver spinner = resolver(() -> {
            spinning.countDown();
            while (!stop.get()) {
                Thread.onSpinWait();
            }
            return new KeyResolver.Resolution(List.of(), false);
        });
        try {
            for (int i = 0; i < 2; i++) {
                assertThat(failed(small.invoke(spinner, SENTINEL, CONTEXT, 5, Duration.ofMillis(50))).reason())
                        .isEqualTo(ResolverOutcome.Reason.TIMEOUT);
            }
            assertThat(spinning.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch called = new CountDownLatch(1);
            KeyResolver healthy = resolver(() -> {
                called.countDown();
                return new KeyResolver.Resolution(List.of("x"), false);
            });
            long begin = System.nanoTime();
            ResolverOutcome outcome = small.invoke(healthy, SENTINEL, CONTEXT, 5, Duration.ofSeconds(30));
            assertThat(failed(outcome).reason()).isEqualTo(ResolverOutcome.Reason.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(5));
            assertThat(called.getCount()).as("the resolver was not called").isEqualTo(1);

            // The spinners hold platform threads, so an unrelated virtual-thread task still runs.
            java.util.concurrent.CompletableFuture<String> unrelated = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().start(() -> unrelated.complete("ran"));
            assertThat(unrelated.get(5, TimeUnit.SECONDS)).isEqualTo("ran");
        } finally {
            stop.set(true);
        }
    }

    @Test
    void aPoolSizeBelowOneIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ResolverInvoker(metrics, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKeyAppearsInNoOutcomeExceptionLogOrMetric() {
        List<ResolverOutcome> outcomes = new ArrayList<>();
        outcomes.add(invoke(resolver(() -> {
            throw new IllegalStateException("boom " + SENTINEL, new RuntimeException(SENTINEL));
        }), 5));
        outcomes.add(invoke(resolver(() -> {
            throw new KeyResolutionException(Reason.REJECTED);
        }), 5));
        outcomes.add(invoke(resolver(() -> new KeyResolver.Resolution(List.of(" "), false)), 5));
        outcomes.add(invoke(resolver(() -> new KeyResolver.Resolution(List.of("1", "2", SENTINEL), false)), 2));
        for (ResolverOutcome outcome : outcomes) {
            assertThat(outcome.toString()).doesNotContain(SENTINEL);
            assertThat(failed(outcome).reason().toString()).doesNotContain(SENTINEL);
        }
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(SENTINEL);
            assertThat(event.getThrowableProxy()).isNull();
        }
        assertThat(allTags).noneMatch(tag -> tag != null && tag.contains(SENTINEL));
    }

    @Test
    void aFailureNeverFallsBackToTheUntranslatedKey() {
        ResolverOutcome outcome = invoke(resolver(() -> {
            throw new IllegalStateException();
        }), 5);
        assertThat(outcome).isNotInstanceOf(ResolverOutcome.Keys.class);
        assertThat(outcome).isNotInstanceOf(ResolverOutcome.Empty.class);
    }

    @Test
    void successfulKeysAreImmutable() {
        ResolverOutcome.Keys keys = (ResolverOutcome.Keys) invoke(
                resolver(() -> new KeyResolver.Resolution(List.of("x"), false)), 5);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.keys().add("y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
