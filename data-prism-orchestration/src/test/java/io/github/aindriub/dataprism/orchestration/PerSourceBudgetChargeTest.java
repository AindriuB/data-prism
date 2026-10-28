package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The read budget is reserved once, per source to be called, before any call (task 246). */
class PerSourceBudgetChargeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
    private static final LlmResponseValidator PASS = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
    private static final ScrubbingEngine OK = (source, ctx) ->
            new ScrubResult(JsonMapper.builder().build().createObjectNode().put("value", "ok"), Set.of());

    @LlmExposedModel
    private record Thing(@InternalIdentifier String id, String value) {
    }

    /** Counts what the orchestrator asks of the budget, and delegates to a real one. */
    private static final class CountingBudget implements ScopeBudget {
        final InMemoryScopeBudget delegate = new InMemoryScopeBudget();
        final AtomicInteger reserveCalls = new AtomicInteger();
        final AtomicInteger tryReadCalls = new AtomicInteger();
        volatile boolean throwOnReserve;
        final List<Integer> reserved = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean tryRead(String scopeId, String subjectId, int budget) {
            tryReadCalls.incrementAndGet();
            return delegate.tryRead(scopeId, subjectId, budget);
        }

        @Override
        public int reads(String scopeId, String subjectId) {
            return delegate.reads(scopeId, subjectId);
        }

        @Override
        public void forget(String scopeId) {
            delegate.forget(scopeId);
        }

        @Override
        public ReadReservation reserve(String scopeId, String subjectId, int reads, int subjectBudget,
                                       int scopeTotalBudget) {
            reserveCalls.incrementAndGet();
            if (throwOnReserve) {
                throw new IllegalStateException("budget store down");
            }
            reserved.add(reads);
            return delegate.reserve(scopeId, subjectId, reads, subjectBudget, scopeTotalBudget);
        }

        @Override
        public void release(ReadReservation reservation, int unused) {
            delegate.release(reservation, unused);
        }

        @Override
        public int scopeReads(String scopeId) {
            return delegate.scopeReads(scopeId);
        }
    }

    private static final class Source implements DataSourceAdapter<Thing> {
        private final String name;
        private final boolean failing;
        final AtomicInteger calls = new AtomicInteger();

        Source(String name, boolean failing) {
            this.name = name;
            this.failing = failing;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public Class<Thing> responseType() {
            return Thing.class;
        }

        @Override
        public Thing fetch(DataRequest request) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("down");
            }
            return new Thing("1", "raw");
        }
    }

    private static PrivacyContext context(String scope) {
        return new PrivacyContext(scope, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private static RequestLimits limits(int maxSources, int subjectBudget, int scopeTotal) {
        return TestLimits.defaults().maxSources(maxSources).scopeReadBudget(subjectBudget).scopeTotalReadBudget(scopeTotal).build();
    }

    private static DefaultContextOrchestrator orchestrator(List<? extends DataSourceAdapter<?>> adapters,
                                                           CountingBudget budget, RequestLimits limits,
                                                           SourceFanOut fanOut) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        return new DefaultContextOrchestrator(
                List.copyOf(adapters), OK, resolver, List.of(PASS),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(event -> { }, CLOCK, "test-1"),
                new PassThroughIdentityResolver(), fanOut, budget, limits,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
    }

    private static SourceFanOut plainFanOut() {
        return new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults());
    }

    private static String refusalCode(Throwable thrown) {
        Throwable t = thrown;
        while (t != null && !(t instanceof PrivacyRefusedException)) {
            t = t.getCause();
        }
        return t == null ? "none" : ((PrivacyRefusedException) t).code();
    }

    @Test
    @DisplayName("one call over three sources reserves three reads in one reserve call, and never uses tryRead")
    void reservesOnePerSourceInOneCall() {
        var budget = new CountingBudget();
        var sources = List.of(new Source("a", false), new Source("b", false), new Source("c", false));

        orchestrator(sources, budget, limits(8, 100, 50_000), plainFanOut())
                .buildContext(ContextRequest.of("THING", "1"), context("S"), caller());

        assertThat(budget.reserved).containsExactly(3);
        assertThat(budget.tryReadCalls.get()).isZero();
        assertThat(budget.reads("S", "1")).isEqualTo(3);
        assertThat(budget.scopeReads("S")).isEqualTo(3);
    }

    @Test
    @DisplayName("a refused reservation refuses the call with SCOPE_READ_BUDGET and calls no source")
    void refusedReservationCallsNothing() {
        var budget = new CountingBudget();
        var sources = List.of(new Source("a", false), new Source("b", false), new Source("c", false));

        assertThatThrownBy(() -> orchestrator(sources, budget, limits(8, 2, 50_000), plainFanOut())
                .buildContext(ContextRequest.of("THING", "1"), context("S"), caller()))
                .satisfies(t -> assertThat(refusalCode(t)).isEqualTo("SCOPE_READ_BUDGET"));

        assertThat(sources).allSatisfy(s -> assertThat(s.calls.get()).isZero());
        assertThat(budget.reads("S", "1")).isZero();
    }

    @Test
    @DisplayName("the scope total refuses a call the subject budget would allow")
    void scopeTotalRefuses() {
        var budget = new CountingBudget();
        var sources = List.of(new Source("a", false), new Source("b", false));
        var orchestrator = orchestrator(sources, budget, limits(8, 100, 3), plainFanOut());
        orchestrator.buildContext(ContextRequest.of("THING", "1"), context("S"), caller());

        assertThatThrownBy(() -> orchestrator.buildContext(ContextRequest.of("THING", "2"), context("S"), caller()))
                .satisfies(t -> assertThat(refusalCode(t)).isEqualTo("SCOPE_READ_BUDGET"));
    }

    @Test
    @DisplayName("more sources than maxSources refuses with SOURCE_SELECTION_TOO_LARGE before any call or reservation")
    void tooManySourcesRefusesBeforeReserving() {
        var budget = new CountingBudget();
        var sources = List.of(new Source("a", false), new Source("b", false), new Source("c", false));

        assertThatThrownBy(() -> orchestrator(sources, budget, limits(2, 100, 50_000), plainFanOut())
                .buildContext(ContextRequest.of("THING", "1"), context("S"), caller()))
                .satisfies(t -> assertThat(refusalCode(t)).isEqualTo("SOURCE_SELECTION_TOO_LARGE"));

        assertThat(sources).allSatisfy(s -> assertThat(s.calls.get()).isZero());
        assertThat(budget.reserveCalls.get()).isZero();
        assertThat(budget.tryReadCalls.get()).isZero();
        assertThat(budget.reads("S", "1")).as("no read was charged").isZero();
        assertThat(budget.scopeReads("S")).isZero();
    }

    @Test
    @DisplayName("a source whose circuit is open is not charged")
    void openCircuitIsNotCharged() {
        var budget = new CountingBudget();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(5), Clock.systemUTC());
        var fanOut = new SourceFanOut(breaker, Clock.systemUTC(), SourceFanOutOptions.defaults());
        var sources = List.of(new Source("good", false), new Source("broken", true));
        var orchestrator = orchestrator(sources, budget, limits(8, 100, 50_000), fanOut);

        orchestrator.buildContext(ContextRequest.of("THING", "1"), context("S"), caller());
        orchestrator.buildContext(ContextRequest.of("THING", "1"), context("S"), caller());

        assertThat(budget.reserved).containsExactly(2, 1);
    }

    @Test
    @DisplayName("compare_entity_sources' request is charged per source called, by the same rule")
    void comparisonPathChargesPerSource() {
        var budget = new CountingBudget();
        var sources = List.of(new Source("a", false), new Source("b", false));

        orchestrator(sources, budget, limits(8, 100, 50_000), plainFanOut())
                .buildContext(ContextRequest.comparison("THING", "1", Set.of(), "compare_entity_sources"),
                        context("S"), caller());

        assertThat(budget.reserved).containsExactly(2);
        assertThat(budget.tryReadCalls.get()).isZero();
    }

    @Test
    @DisplayName("two concurrent requests for one subject never jointly exceed the subject budget")
    void concurrentRequestsRespectTheSubjectBudget() throws Exception {
        for (int round = 0; round < 50; round++) {
            var budget = new CountingBudget();
            var sources = List.of(new Source("a", false), new Source("b", false), new Source("c", false));
            var orchestrator = orchestrator(sources, budget, limits(8, 4, 50_000), plainFanOut());

            int refused = runTogether(orchestrator, "1", "1", "S", "S");

            assertThat(refused).as("exactly one of two 3-read requests fits a budget of 4").isEqualTo(1);
            assertThat(budget.reads("S", "1")).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("two concurrent requests for different subjects never jointly exceed the scope total")
    void concurrentRequestsRespectTheScopeTotal() throws Exception {
        for (int round = 0; round < 50; round++) {
            var budget = new CountingBudget();
            var sources = List.of(new Source("a", false), new Source("b", false), new Source("c", false));
            var orchestrator = orchestrator(sources, budget, limits(8, 100, 5), plainFanOut());

            int refused = runTogether(orchestrator, "1", "2", "S", "S");

            assertThat(refused).isEqualTo(1);
            assertThat(budget.scopeReads("S")).isEqualTo(3);
        }
    }

    /** Starts two calls together and returns how many were refused for the budget. */
    private static int runTogether(DefaultContextOrchestrator orchestrator, String subjectA, String subjectB,
                                   String scopeA, String scopeB) throws Exception {
        var go = new CountDownLatch(1);
        var first = TestAsync.supply(() -> call(orchestrator, go, subjectA, scopeA));
        var second = TestAsync.supply(() -> call(orchestrator, go, subjectB, scopeB));
        go.countDown();
        return first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);
    }

    private static int call(DefaultContextOrchestrator orchestrator, CountDownLatch go, String subject, String scope) {
        try {
            go.await();
            orchestrator.buildContext(ContextRequest.of("THING", subject), context(scope), caller());
            return 0;
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            assertThat(refusalCode(e)).isEqualTo("SCOPE_READ_BUDGET");
            return 1;
        }
    }

    @Test
    @DisplayName("reads reserved for sources never started before the deadline are released")
    void unstartedReadsAreReleased() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var entered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        List<DataSourceAdapter<?>> all = new ArrayList<>();
        for (String name : List.of("b1", "b2", "b3")) {
            all.add(new DataSourceAdapter<Thing>() {
                @Override
                public String sourceName() {
                    return name;
                }

                @Override
                public Class<Thing> responseType() {
                    return Thing.class;
                }

                @Override
                public Thing fetch(DataRequest request) {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                    return null;
                }
            });
        }
        var budget = new CountingBudget();
        var limits = TestLimits.defaults().maxConcurrency(1).build();
        var orchestrator = orchestrator(all, budget, limits, fanOut);

        var running = TestAsync.run(() -> assertThatThrownBy(() ->
                orchestrator.buildContext(ContextRequest.of("THING", "1"), context("S"), caller()))
                .satisfies(t -> assertThat(refusalCode(t)).isEqualTo("NO_SOURCE_DATA")));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofSeconds(10));
        running.get(30, TimeUnit.SECONDS);

        assertThat(budget.reserved).containsExactly(3);
        assertThat(calls.get()).as("only one source got the single permit").isEqualTo(1);
        assertThat(budget.reads("S", "1")).as("only the read that started stays charged").isEqualTo(1);
        assertThat(issued(budget, "S")).as("a partly used reservation is settled too").isZero();
    }

    private static SourceCircuitBreaker breakerOpenPastCooldown(SourceFanOutDeadlineTest.MutableClock clock) {
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        breaker.record(new SourceOutcome("s", SourceOutcome.Status.FAILED, Duration.ZERO, "x"));
        clock.advance(Duration.ofSeconds(6));
        return breaker;
    }

    @Test
    @DisplayName("a refused reservation hands back the half-open trial it took, so the source can be tried again")
    void refusedReservationFreesTheTrial() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = breakerOpenPastCooldown(clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var sources = List.of(new Source("s", false), new Source("t", false));

        assertThatThrownBy(() -> orchestrator(sources, new CountingBudget(), limits(8, 1, 50_000), fanOut)
                .buildContext(ContextRequest.of("THING", "1"), context("S"), caller()))
                .satisfies(t -> assertThat(refusalCode(t)).isEqualTo("SCOPE_READ_BUDGET"));

        assertThat(sources).allSatisfy(s -> assertThat(s.calls.get()).isZero());
        assertThat(breaker.admit("s").holdsTrial()).as("the trial was not left taken").isTrue();
    }

    @Test
    @DisplayName("a reservation that throws hands back the half-open trial it took")
    void throwingReservationFreesTheTrial() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = breakerOpenPastCooldown(clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var budget = new CountingBudget();
        budget.throwOnReserve = true;
        var sources = List.of(new Source("s", false));

        assertThatThrownBy(() -> orchestrator(sources, budget, limits(8, 100, 50_000), fanOut)
                .buildContext(ContextRequest.of("THING", "1"), context("S"), caller()))
                .isInstanceOf(RuntimeException.class);

        assertThat(sources.get(0).calls.get()).isZero();
        assertThat(breaker.admit("s").holdsTrial()).as("the trial was not left taken").isTrue();
    }

    /** How many reservations the in-memory budget still tracks for a scope. */
    private static int issued(CountingBudget budget, String scope) throws Exception {
        Field scopes = InMemoryScopeBudget.class.getDeclaredField("scopes");
        scopes.setAccessible(true);
        Object state = ((Map<?, ?>) scopes.get(budget.delegate)).get(scope);
        if (state == null) {
            return 0;
        }
        Field issued = state.getClass().getDeclaredField("issued");
        issued.setAccessible(true);
        return ((Set<?>) issued.get(state)).size();
    }

    @Test
    @DisplayName("a request that uses every read leaves no reservation tracked")
    void fullyUsedReservationsAreNotKept() throws Exception {
        var budget = new CountingBudget();
        var sources = List.of(new Source("a", false), new Source("b", false), new Source("c", false));
        var orchestrator = orchestrator(sources, budget, limits(8, 1_000, 50_000), plainFanOut());

        for (int request = 0; request < 25; request++) {
            orchestrator.buildContext(ContextRequest.of("THING", "1"), context("S"), caller());
        }

        assertThat(budget.reads("S", "1")).as("every read stays charged").isEqualTo(75);
        assertThat(issued(budget, "S")).isZero();
    }

    @Test
    @DisplayName("an execution that fails gives every read back, hands back the half-open trial, and tracks nothing")
    void failedExecutionSettlesEverything() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = breakerOpenPastCooldown(clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        fanOut.bulkheadObserver(bulkhead -> {
            throw new IllegalStateException("observer failed");
        });
        var budget = new CountingBudget();
        var sources = List.of(new Source("s", false), new Source("t", false));

        assertThatThrownBy(() -> orchestrator(sources, budget, limits(8, 100, 50_000), fanOut)
                .buildContext(ContextRequest.of("THING", "1"), context("S"), caller()))
                .isInstanceOf(RuntimeException.class);

        assertThat(sources).allSatisfy(s -> assertThat(s.calls.get()).isZero());
        assertThat(budget.reserved).containsExactly(2);
        assertThat(budget.reads("S", "1")).as("sources never called cost nothing").isZero();
        assertThat(issued(budget, "S")).isZero();
        assertThat(breaker.admit("s").holdsTrial()).as("the trial is free again").isTrue();
    }
}
