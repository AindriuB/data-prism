package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.link.LinkAttemptGate.Exhausted;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate.ExhaustedCause;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate.GateDecision;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate.Invalid;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate.Proceed;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate.Refusal;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter.Admission;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter.LockState;
import io.github.aindriub.dataprism.core.link.LinkTestSupport.MapKeys;
import io.github.aindriub.dataprism.core.link.LinkTestSupport.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.aindriub.dataprism.core.link.LinkTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkAttemptGateTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int LIMIT = 20;

    private static final LinkOpenResult.Refused AUTH_FAILED = new LinkOpenResult.Refused(LinkReason.AUTH_FAILED, true);
    private static final LinkOpenResult.Opened OPENED =
            new LinkOpenResult.Opened(follow(5), T0.plusSeconds(60), "k1");

    private TestClock clock;
    private InMemoryLinkAttemptLimiter limiter;
    private SpyOpener opener;
    private LinkAttemptGate gate;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        limiter = new InMemoryLinkAttemptLimiter(LIMIT, WINDOW, clock);
        opener = new SpyOpener(token -> token.equals("good") ? OPENED
                : token.equals("expired") ? new LinkOpenResult.Refused(LinkReason.EXPIRED, false) : AUTH_FAILED);
        gate = new LinkAttemptGate(opener, limiter);
    }

    /** An opener that records its calls. */
    private static final class SpyOpener implements LinkOpener {
        final AtomicInteger calls = new AtomicInteger();
        final Function<String, LinkOpenResult> behaviour;

        SpyOpener(Function<String, LinkOpenResult> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public LinkOpenResult open(String token, LinkBinding binding, Set<LinkRelation> accepted) {
            calls.incrementAndGet();
            return behaviour.apply(token);
        }
    }

    /** A limiter that records its calls and answers from suppliers. */
    private static final class SpyLimiter implements LinkAttemptLimiter {
        final AtomicInteger locks = new AtomicInteger();
        final AtomicInteger records = new AtomicInteger();
        Supplier<LockState> lock = () -> LockState.UNLOCKED;
        Supplier<Admission> record = () -> Admission.WITHIN_LIMIT;

        @Override
        public LockState lockState(AttemptSubject subject) {
            locks.incrementAndGet();
            return lock.get();
        }

        @Override
        public Admission recordFailure(AttemptSubject subject) {
            records.incrementAndGet();
            return record.get();
        }
    }

    private GateDecision open(String token, String principal) {
        return gate.open(token, binding("case-1", principal), follows());
    }

    private void fail(int times, String principal) {
        for (int i = 0; i < times; i++) {
            open("bad", principal);
        }
    }

    // ---- one call, no bypass ----

    @Test
    void aLockedPrincipalIsRefusedBeforeTheTokenIsOpened() {
        fail(LIMIT, "alice");
        int before = opener.calls.get();
        assertThat(open("good", "alice")).isEqualTo(new Exhausted(ExhaustedCause.LOCKED));
        assertThat(open("expired", "alice")).isEqualTo(new Exhausted(ExhaustedCause.LOCKED));
        assertThat(opener.calls.get()).isEqualTo(before);
        // Another principal in the same scope, and the same principal in another scope, are unaffected.
        assertThat(open("good", "bob")).isInstanceOf(Proceed.class);
        assertThat(gate.open("good", binding("case-2", "alice"), follows())).isInstanceOf(Proceed.class);
        // The window ending lifts the lock.
        clock.advance(WINDOW);
        assertThat(open("good", "alice")).isInstanceOf(Proceed.class);
    }

    @Test
    void theCountedFailureThatReachesTheLimitIsStillInvalid() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(open("bad", "alice")).isEqualTo(new Invalid(LinkReason.AUTH_FAILED));
        }
        assertThat(open("bad", "alice")).isEqualTo(new Exhausted(ExhaustedCause.LOCKED));
    }

    // ---- races ----

    @Test
    void exactlyTheLimitOfConcurrentForgedAttemptsAreInvalid() throws Exception {
        int n = 64;
        for (int round = 0; round < 50; round++) {
            InMemoryLinkAttemptLimiter fresh = new InMemoryLinkAttemptLimiter(LIMIT, WINDOW, clock);
            LinkAttemptGate racing = new LinkAttemptGate(new SpyOpener(token -> AUTH_FAILED), fresh);
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<GateDecision> results = new ArrayList<>();
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<GateDecision>> futures = new ArrayList<>();
                for (int t = 0; t < n; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return racing.open("forged", binding("case-1", "alice"), follows());
                    }));
                }
                start.countDown();
                for (Future<GateDecision> f : futures) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
            long invalid = results.stream().filter(r -> r instanceof Invalid).count();
            long locked = results.stream().filter(r -> r.equals(new Exhausted(ExhaustedCause.LOCKED))).count();
            long over = results.stream().filter(r -> r.equals(new Exhausted(ExhaustedCause.OVER_LIMIT))).count();
            assertThat(results).hasSize(n);
            assertThat(invalid).isEqualTo(LIMIT);
            assertThat(invalid + locked + over).isEqualTo(n);
        }
    }

    /** An opener that, for the named tokens, waits until the given number of callers are inside it. */
    private static final class LatchingOpener implements LinkOpener {
        final CountDownLatch inside;
        final CountDownLatch release = new CountDownLatch(1);
        final Function<String, LinkOpenResult> behaviour;

        LatchingOpener(int waiting, Function<String, LinkOpenResult> behaviour) {
            this.inside = new CountDownLatch(waiting);
            this.behaviour = behaviour;
        }

        @Override
        public LinkOpenResult open(String token, LinkBinding binding, Set<LinkRelation> accepted) {
            if (token.startsWith("slow")) {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return behaviour.apply(token);
        }
    }

    @Test
    void twoAttemptsPastTheCheckAtNineteenGiveOneInvalidAndOneOverTheLimit() throws Exception {
        LatchingOpener latching = new LatchingOpener(2, token -> AUTH_FAILED);
        LinkAttemptGate racing = new LinkAttemptGate(latching, limiter);
        for (int i = 0; i < LIMIT - 1; i++) {
            racing.open("bad", binding("case-1", "alice"), follows());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GateDecision> one = pool.submit(() -> racing.open("slow-1", binding("case-1", "alice"), follows()));
            Future<GateDecision> two = pool.submit(() -> racing.open("slow-2", binding("case-1", "alice"), follows()));
            assertThat(latching.inside.await(5, TimeUnit.SECONDS)).isTrue();
            latching.release.countDown();
            List<GateDecision> results = List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(
                    new Invalid(LinkReason.AUTH_FAILED), new Exhausted(ExhaustedCause.OVER_LIMIT));
        } finally {
            latching.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aValidLinkPastTheCheckAtNineteenProceedsEvenIfAForgedAttemptLocksFirst_limitPlusInFlight() throws Exception {
        // The stated bound: the guesses that can reveal a success are the limit plus the attempts already past the
        // lock check when the lock engaged. This documents it and asserts nothing stronger.
        LatchingOpener latching = new LatchingOpener(1, token -> token.startsWith("slow") ? OPENED : AUTH_FAILED);
        LinkAttemptGate racing = new LinkAttemptGate(latching, limiter);
        for (int i = 0; i < LIMIT - 1; i++) {
            racing.open("bad", binding("case-1", "alice"), follows());
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<GateDecision> valid = pool.submit(() -> racing.open("slow-valid", binding("case-1", "alice"),
                    follows()));
            assertThat(latching.inside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(racing.open("bad", binding("case-1", "alice"), follows()))
                    .isEqualTo(new Invalid(LinkReason.AUTH_FAILED));
            latching.release.countDown();
            assertThat(valid.get(5, TimeUnit.SECONDS)).isInstanceOf(Proceed.class);
        } finally {
            latching.release.countDown();
            pool.shutdownNow();
        }
        assertThat(racing.open("good", binding("case-1", "alice"), follows()))
                .isEqualTo(new Exhausted(ExhaustedCause.LOCKED));
    }

    // ---- gating on counted() ----

    @Test
    void uncountedRefusalsRecordNothing() {
        for (LinkReason reason : new LinkReason[] {LinkReason.MALFORMED, LinkReason.KEY_UNAVAILABLE}) {
            SpyLimiter spy = new SpyLimiter();
            LinkAttemptGate g = new LinkAttemptGate((t, b, a) -> new LinkOpenResult.Refused(reason, false), spy);
            for (int i = 0; i < 10; i++) {
                GateDecision decision = g.open("t", binding("case-1", "alice"), follows());
                assertThat(decision).isEqualTo(new Invalid(reason));
                assertThat(((Refusal) decision).auditValue()).isEqualTo("REFUSED:LINK_" + reason.name());
                assertThat(((Refusal) decision).clientCode()).isEqualTo("LINK_INVALID");
            }
            assertThat(spy.records.get()).isZero();
            assertThat(spy.locks.get()).isEqualTo(10);
        }
        // The real limiter's counter is untouched by the outage row.
        SpyOpener outage = new SpyOpener(t -> new LinkOpenResult.Refused(LinkReason.KEY_UNAVAILABLE, false));
        LinkAttemptGate g = new LinkAttemptGate(outage, limiter);
        for (int i = 0; i < 30; i++) {
            g.open("t", binding("case-1", "alice"), follows());
        }
        assertThat(limiter.lockState(new AttemptSubject("case-1", "alice"))).isEqualTo(LockState.UNLOCKED);
        assertThat(limiter.size()).isZero();
    }

    @Test
    void aCountedRefusalRecordsOnceAndIsGatedOnTheFlagNotTheReason() {
        SpyLimiter spy = new SpyLimiter();
        assertThat(new LinkAttemptGate((t, b, a) -> AUTH_FAILED, spy).open("t", binding("s", "p"), follows()))
                .isEqualTo(new Invalid(LinkReason.AUTH_FAILED));
        assertThat(spy.records.get()).isEqualTo(1);
        // MALFORMED is a counted reason by default, but an authenticated-and-broken payload is not counted.
        new LinkAttemptGate((t, b, a) -> new LinkOpenResult.Refused(LinkReason.MALFORMED, false), spy)
                .open("t", binding("s", "p"), follows());
        assertThat(spy.records.get()).isEqualTo(1);
        // The converse: a refusal flagged counted is recorded whatever the reason's default.
        new LinkAttemptGate((t, b, a) -> new LinkOpenResult.Refused(LinkReason.EXPIRED, true), spy)
                .open("t", binding("s", "p"), follows());
        assertThat(spy.records.get()).isEqualTo(2);
    }

    // ---- malformed inputs ----

    @Test
    void malformedInputsAreInvalidWithNoCallsAndNoThrow() {
        SpyLimiter spy = new SpyLimiter();
        SpyOpener spyOpener = new SpyOpener(t -> OPENED);
        LinkAttemptGate g = new LinkAttemptGate(spyOpener, spy);
        Instant end = T0.plusSeconds(60);
        String over = "o".repeat(65_536);
        List<Supplier<GateDecision>> cases = List.of(
                () -> g.open(null, binding("s", "p"), follows()),
                () -> g.open("", binding("s", "p"), follows()),
                () -> g.open("t", null, follows()),
                () -> g.open("t", binding("s", "p"), null),
                () -> g.open("t", new LinkBinding("", "p", end, end), follows()),
                () -> g.open("t", new LinkBinding("s", "", end, end), follows()),
                () -> g.open("t", new LinkBinding(over, "p", end, end), follows()),
                () -> g.open("t", new LinkBinding("s", over, end, end), follows()));
        for (Supplier<GateDecision> call : cases) {
            assertThat(call.get()).isEqualTo(new Invalid(LinkReason.MALFORMED));
        }
        assertThat(spy.locks.get()).isZero();
        assertThat(spy.records.get()).isZero();
        assertThat(spyOpener.calls.get()).isZero();
        String max = "m".repeat(65_535);
        assertThat(g.open("t", new LinkBinding(max, max, end, end), follows())).isInstanceOf(Proceed.class);
        assertThat(spyOpener.calls.get()).isEqualTo(1);
    }

    // ---- fail closed ----

    private record Row(String name, Supplier<LockState> lock, Supplier<Admission> record,
            Function<String, LinkOpenResult> open, GateDecision expected, String code, String audit,
            boolean opens, boolean records) {}

    private static <T> Supplier<T> throwing() {
        return () -> {
            throw new IllegalStateException("limiter detail SENTINEL");
        };
    }

    @Test
    void everyFailureIsRefusedWithItsCauseAndNoExceptionEscapes() {
        Function<String, LinkOpenResult> forged = t -> AUTH_FAILED;
        Function<String, LinkOpenResult> opens = t -> OPENED;
        List<Row> rows = List.of(
                new Row("lock unavailable", () -> LockState.UNAVAILABLE, () -> Admission.WITHIN_LIMIT, opens,
                        new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE), "LINK_CHECK_UNAVAILABLE",
                        "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE", false, false),
                new Row("lock null", () -> null, () -> Admission.WITHIN_LIMIT, opens,
                        new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE), "LINK_CHECK_UNAVAILABLE",
                        "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE", false, false),
                new Row("lock throws", throwing(), () -> Admission.WITHIN_LIMIT, opens,
                        new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE), "LINK_CHECK_UNAVAILABLE",
                        "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE", false, false),
                new Row("lock locked", () -> LockState.LOCKED, () -> Admission.WITHIN_LIMIT, opens,
                        new Exhausted(ExhaustedCause.LOCKED), "LINK_ATTEMPTS_EXHAUSTED",
                        "REFUSED:LINK_ATTEMPTS_LOCKED", false, false),
                new Row("opener throws", () -> LockState.UNLOCKED, () -> Admission.WITHIN_LIMIT, t -> {
                    throw new IllegalStateException("opener detail SENTINEL");
                }, new Exhausted(ExhaustedCause.OPEN_FAILED), "LINK_INVALID",
                        "REFUSED:LINK_ATTEMPTS_OPEN_FAILED", true, false),
                new Row("opener null", () -> LockState.UNLOCKED, () -> Admission.WITHIN_LIMIT, t -> null,
                        new Exhausted(ExhaustedCause.OPEN_FAILED), "LINK_INVALID",
                        "REFUSED:LINK_ATTEMPTS_OPEN_FAILED", true, false),
                new Row("record unavailable", () -> LockState.UNLOCKED, () -> Admission.UNAVAILABLE, forged,
                        new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE), "LINK_CHECK_UNAVAILABLE",
                        "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE", true, true),
                new Row("record null", () -> LockState.UNLOCKED, () -> null, forged,
                        new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE), "LINK_CHECK_UNAVAILABLE",
                        "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE", true, true),
                new Row("record throws", () -> LockState.UNLOCKED, throwing(), forged,
                        new Exhausted(ExhaustedCause.LIMITER_UNAVAILABLE), "LINK_CHECK_UNAVAILABLE",
                        "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE", true, true),
                new Row("record ceiling", () -> LockState.UNLOCKED, () -> Admission.CEILING, forged,
                        new Exhausted(ExhaustedCause.CEILING), "LINK_ATTEMPTS_EXHAUSTED",
                        "REFUSED:LINK_ATTEMPTS_CEILING", true, true),
                new Row("record over limit", () -> LockState.UNLOCKED, () -> Admission.OVER_LIMIT, forged,
                        new Exhausted(ExhaustedCause.OVER_LIMIT), "LINK_ATTEMPTS_EXHAUSTED",
                        "REFUSED:LINK_ATTEMPTS_OVER_LIMIT", true, true));
        for (Row row : rows) {
            SpyLimiter spy = new SpyLimiter();
            spy.lock = row.lock();
            spy.record = row.record();
            SpyOpener spyOpener = new SpyOpener(row.open());
            GateDecision decision = new LinkAttemptGate(spyOpener, spy)
                    .open("t", binding("case-1", "alice"), follows());
            assertThat(decision).as(row.name()).isEqualTo(row.expected());
            Refusal refusal = (Refusal) decision;
            assertThat(refusal.clientCode()).as(row.name()).isEqualTo(row.code());
            assertThat(refusal.auditValue()).as(row.name()).isEqualTo(row.audit());
            assertThat(decision.toString()).as(row.name()).doesNotContain("SENTINEL");
            assertThat(spyOpener.calls.get() > 0).as(row.name() + " opens").isEqualTo(row.opens());
            assertThat(spy.records.get() > 0).as(row.name() + " records").isEqualTo(row.records());
        }
    }

    @Test
    void anErrorFromTheOpenerPropagates() {
        LinkAttemptGate g = new LinkAttemptGate((t, b, a) -> {
            throw new AssertionError("an error");
        }, new SpyLimiter());
        assertThatThrownBy(() -> g.open("t", binding("s", "p"), follows())).isInstanceOf(AssertionError.class);
    }

    @Test
    void anErrorFromTheLimiterPropagates() {
        SpyLimiter spy = new SpyLimiter();
        spy.lock = () -> {
            throw new AssertionError("an error");
        };
        LinkAttemptGate g = new LinkAttemptGate((t, b, a) -> OPENED, spy);
        assertThatThrownBy(() -> g.open("t", binding("s", "p"), follows())).isInstanceOf(AssertionError.class);
    }

    @Test
    void anInvalidDecisionCarriesItsReasonsCodeAndAudit() {
        Invalid invalid = new Invalid(LinkReason.AUTH_FAILED);
        assertThat(invalid.clientCode()).isEqualTo("LINK_INVALID");
        assertThat(invalid.auditValue()).isEqualTo("REFUSED:LINK_AUTH_FAILED");
        for (ExhaustedCause cause : ExhaustedCause.values()) {
            assertThat(new Exhausted(cause).auditValue()).isEqualTo("REFUSED:LINK_ATTEMPTS_" + cause.name());
        }
    }

    // ---- the ceiling (OD-269-3) ----

    @Test
    void atTheCeilingANewSubjectIsExhaustedButAValidLinkStillProceeds() {
        InMemoryLinkAttemptLimiter small = new InMemoryLinkAttemptLimiter(LIMIT, WINDOW, clock, 4);
        LinkAttemptGate g = new LinkAttemptGate(opener, small);
        for (int i = 1; i <= 4; i++) {
            assertThat(g.open("bad", binding("case-1", "p" + i), follows()))
                    .isEqualTo(new Invalid(LinkReason.AUTH_FAILED));
        }
        assertThat(g.open("bad", binding("case-1", "p5"), follows())).isEqualTo(new Exhausted(ExhaustedCause.CEILING));
        assertThat(small.size()).isEqualTo(4);
        assertThat(g.open("good", binding("case-1", "p5"), follows())).isInstanceOf(Proceed.class);
        assertThat(g.open("good", binding("case-1", "p6"), follows())).isInstanceOf(Proceed.class);
    }

    @Test
    void theFifthSubjectCanKeepGuessingAfterTheCeilingAndStillGetsAValidLinkThrough_od269x3() {
        InMemoryLinkAttemptLimiter small = new InMemoryLinkAttemptLimiter(LIMIT, WINDOW, clock, 4);
        LinkAttemptGate g = new LinkAttemptGate(opener, small);
        for (int i = 1; i <= 4; i++) {
            g.open("bad", binding("case-1", "p" + i), follows());
        }
        for (int i = 0; i < 100; i++) {
            assertThat(g.open("bad", binding("case-1", "p5"), follows()))
                    .isEqualTo(new Exhausted(ExhaustedCause.CEILING));
        }
        assertThat(g.open("good", binding("case-1", "p5"), follows())).isInstanceOf(Proceed.class);
    }

    // ---- no leak, shape ----

    @Test
    void noDecisionOrToStringNamesAnId() {
        String scope = "scope-SENTINEL-41";
        String principal = "principal-SENTINEL-42";
        LinkBinding sentinel = binding(scope, principal);
        List<GateDecision> decisions = new ArrayList<>(List.of(
                gate.open("good", sentinel, follows()), gate.open("bad", sentinel, follows()),
                gate.open("", sentinel, follows())));
        for (int i = 0; i < LIMIT; i++) {
            decisions.add(gate.open("bad", sentinel, follows()));
        }
        for (GateDecision decision : decisions) {
            assertThat(decision.toString()).doesNotContain(scope).doesNotContain(principal);
            if (decision instanceof Refusal refusal) {
                assertThat(refusal.auditValue()).doesNotContain(scope).doesNotContain(principal);
            }
        }
        assertThat(gate.toString()).doesNotContain(scope).doesNotContain(principal);
        assertThat(limiter.toString()).doesNotContain(scope).doesNotContain(principal);
        assertThat(Arrays.toString(ExhaustedCause.values())).contains("OPEN_FAILED");
    }

    @Test
    void thePublicGateOpensARealTokenThroughTheCodec() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32));
        LinkCodec codec = codec(provider, settings("k1", true, Duration.ofSeconds(30)), clock);
        LinkAttemptGate real = new LinkAttemptGate(codec, limiter);
        LinkBinding alice = binding("case-1", "alice");
        String valid = codec.seal(follow(5), alice).orElseThrow().token();
        assertThat(real.open(valid, alice, follows())).isInstanceOf(Proceed.class);
        assertThat(real.open("!bad", alice, follows())).isEqualTo(new Invalid(LinkReason.MALFORMED));
        for (int i = 0; i < LIMIT - 1; i++) {
            real.open("!bad", alice, follows());
        }
        assertThat(real.open(valid, alice, follows())).isEqualTo(new Exhausted(ExhaustedCause.LOCKED));
    }
}
