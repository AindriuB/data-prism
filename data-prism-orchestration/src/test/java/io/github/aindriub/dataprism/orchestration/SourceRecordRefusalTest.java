package io.github.aindriub.dataprism.orchestration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.engine.SourceTree;
import io.github.aindriub.dataprism.core.engine.SourceValues;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * One source's bad value refuses that source alone (task 288): the walker refuses inside the per-source
 * {@code try}, and {@code SourceValues.prohibited}, which runs outside it, throws nothing. Source {@code a}
 * returns the bad value and source {@code b} is a well-formed model; for each case {@code a} is
 * {@code REFUSED}, {@code b} is {@code ANSWERED}, and the request does not fail. All values are invented.
 */
class SourceRecordRefusalTest {

    private static final String EMAIL = "ada@example.invalid";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");

    record Contact(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    /** Repro 6: a record at an Object position. */
    @LlmExposedModel
    record Holder(@InternalIdentifier String id, @NonSensitive(reason = "x") Object any) {
    }

    @LlmExposedModel
    record Good(@InternalIdentifier String id, @NonSensitive(reason = "x") String note) {
    }

    @LlmExposedModel
    record Lazy(@InternalIdentifier String id,
                @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                        suggestedAction = PrivacyAction.REDACT) List<String> v) {
    }

    @LlmExposedModel
    record Polluted(@InternalIdentifier String id, @NonSensitive(reason = "x") Map<String, String> v) {
    }

    @LlmExposedModel
    record Dated(@InternalIdentifier String id, @NonSensitive(reason = "x") Calendar when) {
    }

    @LlmExposedModel
    record Node(@InternalIdentifier String id, @NonSensitive(reason = "x") Chain next) {
    }

    record Chain(Chain next) {
    }

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void cleanUp() {
        release.countDown();
        Thread.interrupted();
    }

    // ---- harness -------------------------------------------------------------------------------

    private record Run(ContextResponse response, List<AuditEvent> events) {
        String status(String source) {
            return response.sources().get(source).status();
        }

        String disposition(String source) {
            return events.get(0).fieldDispositions().get(source + ":<refused>");
        }
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1",
                Set.of(Capability.EXPOSE_SOURCE_NAMES));
    }

    private static Map<String, PrivacyProfile> defaultProfiles() {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return PrivacyProfiles.fromYaml(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataSourceAdapter<?> adapter(String name, Class<?> type, Object record) {
        return new DataSourceAdapter() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class responseType() {
                return type;
            }

            @Override
            public Object fetch(DataRequest request) {
                return record;
            }
        };
    }

    private static DefaultContextOrchestrator orchestrator(List<AuditEvent> events, Object aRecord) {
        var resolver = new DefaultFieldMetadataResolver();
        var scrubber = new JsonTreeScrubbingEngine(resolver, new ProfilePrivacyPolicyResolver(defaultProfiles()),
                (subject, namespace, ctx) -> "synthetic");
        return new DefaultContextOrchestrator(
                List.of(adapter("a", aRecord.getClass(), aRecord),
                        adapter("b", Good.class, new Good("b-1", "plain note"))),
                scrubber, resolver,
                List.of(new RawValueLeakValidator(), new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS),
                new AuditRecorder(events::add, CLOCK, "test-1"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
    }

    private static Run run(Object aRecord) {
        var events = new ArrayList<AuditEvent>();
        ContextResponse response = orchestrator(events, aRecord)
                .buildContext(ContextRequest.of("THING", "1"), context(), caller());
        return new Run(response, events);
    }

    private static Set<String> prohibited(Object record) {
        return SourceValues.prohibited(record, new DefaultFieldMetadataResolver());
    }

    /** {@code a} is refused alone, with {@code code}; {@code b} answers; the request returned. */
    private static void assertRefusedAlone(Run run, String code) {
        assertThat(run.status("a")).isEqualTo("REFUSED");
        assertThat(run.status("b")).isEqualTo("ANSWERED");
        assertThat(run.disposition("a")).isEqualTo("REFUSED:" + code);
        assertThat(run.response().sources().get("a").entity()).isNull();
    }

    // ---- the two-source tests ------------------------------------------------------------------

    @Test
    @DisplayName("repro 6: a record at an Object position refuses its source alone, and its email is prohibited")
    void aRecordAtAnObjectPositionRefusesItsSourceAlone() {
        var holder = new Holder("a-1", new Contact(EMAIL));

        assertRefusedAlone(run(holder), "SOURCE_MODEL_RECORD_NOT_DECLARED");
        assertThat(prohibited(holder)).contains(EMAIL);
    }

    @Test
    @DisplayName("a 10,000-link chain is refused TOO_DEEP, and prohibited returns")
    void aDeepChainRefusesItsSourceAlone() {
        Chain chain = new Chain(null);
        for (int i = 0; i < 10_000; i++) {
            chain = new Chain(chain);
        }
        var node = new Node("a-1", chain);

        assertRefusedAlone(run(node), "TOO_DEEP");
    }

    // ---- futures and other holders -------------------------------------------------------------

    @Test
    @DisplayName("an unfinished, cancelled or failed CompletableFuture refuses its source at once; nothing waits")
    void unfinishedFutures() {
        CompletableFuture<Object> unfinished = new CompletableFuture<>();
        CompletableFuture<Object> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException(EMAIL));
        for (CompletableFuture<Object> future : List.of(unfinished, cancelled, failed)) {
            var holder = new Holder("a-1", future);
            Run run = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> run(holder));
            assertRefusedAlone(run, "SOURCE_MODEL_NOT_A_RECORD");
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> prohibited(holder));
        }
    }

    @Test
    @DisplayName("a completed CompletableFuture holding a record puts the record's email in the prohibited set")
    void aCompletedFutureIsRead() {
        var holder = new Holder("a-1", CompletableFuture.completedFuture(new Contact(EMAIL)));

        assertRefusedAlone(run(holder), "SOURCE_MODEL_NOT_A_RECORD");
        assertThat(prohibited(holder)).contains(EMAIL);
    }

    @Test
    @DisplayName("a ThreadLocal is not read: a withInitial supplier is never run")
    void aThreadLocalIsNotRead() {
        AtomicInteger calls = new AtomicInteger();
        ThreadLocal<Object> local = ThreadLocal.withInitial(() -> {
            calls.incrementAndGet();
            return new Contact(EMAIL);
        });
        var holder = new Holder("a-1", local);

        assertRefusedAlone(run(holder), "SOURCE_MODEL_NOT_A_RECORD");
        assertThat(calls).hasValue(0);
    }

    final class UserFuture implements Future<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            entered.set(true);
            return false;
        }

        @Override
        public boolean isCancelled() {
            entered.set(true);
            return false;
        }

        @Override
        public boolean isDone() {
            entered.set(true);
            return true;
        }

        @Override
        public Object get() throws InterruptedException {
            entered.set(true);
            release.await();
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            entered.set(true);
            release.await();
            return null;
        }
    }

    @Test
    @DisplayName("a user class implementing Future is never called: neither isDone() nor get()")
    void aUserFutureIsNeverCalled() {
        UserFuture future = new UserFuture();
        var holder = new Holder("a-1", future);

        Run run = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> run(holder));

        assertRefusedAlone(run, "SOURCE_MODEL_NOT_A_RECORD");
        assertThat(future.entered).isFalse();
    }

    final class StateFuture extends CompletableFuture<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        @Override
        public State state() {
            entered.set(true);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return State.SUCCESS;
        }
    }

    @Test
    @DisplayName("a CompletableFuture subclass overriding state() is prohibited-unknown and never entered")
    void aFutureSubclassIsNeverEntered() {
        StateFuture future = new StateFuture();
        future.complete(new Contact(EMAIL));
        var holder = new Holder("a-1", future);

        Run run = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> run(holder));

        assertRefusedAlone(run, "SOURCE_MODEL_NOT_A_RECORD");
        assertThat(future.entered).isFalse();
        assertThat(prohibited(holder)).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("a Stream holder is not consumed by the lenient walk and refuses its source")
    void aStreamIsNotConsumed() {
        AtomicInteger advances = new AtomicInteger();
        Stream<String> stream = StreamSupport.stream(new java.util.Spliterators.AbstractSpliterator<String>(1, 0) {
            @Override
            public boolean tryAdvance(Consumer<? super String> action) {
                advances.incrementAndGet();
                return false;
            }
        }, false);

        Run run = run(new Holder("a-1", stream));

        assertRefusedAlone(run, "SOURCE_MODEL_NOT_A_RECORD");
        assertThat(advances).hasValue(0);
    }

    // ---- read failures -------------------------------------------------------------------------

    /** A list whose iterator() throws what the thrower makes. */
    static final class LazyList extends AbstractList<String> {
        private final Runnable thrower;

        LazyList(Runnable thrower) {
            this.thrower = thrower;
        }

        @Override
        public Iterator<String> iterator() {
            thrower.run();
            return Collections.emptyIterator();
        }

        @Override
        public String get(int index) {
            throw new IndexOutOfBoundsException();
        }

        @Override
        public int size() {
            return 0;
        }
    }

    private static List<String> lazy(Runnable thrower) {
        return Collections.unmodifiableList(new LazyList(thrower));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    @DisplayName("lazy list: iterator() throws, and the source is refused with SOURCE_VALUE_READ_FAILED")
    void aLazyListThatThrows() {
        var source = new Lazy("a-1", lazy(() -> {
            throw new IllegalStateException(EMAIL);
        }));

        Run run = run(source);

        assertRefusedAlone(run, "SOURCE_VALUE_READ_FAILED");
        assertThat(prohibited(source)).isNotNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("heap-polluted map: an Integer key and a record value are read by runtime class, never cast")
    void aHeapPollutedMap() {
        Map<String, String> polluted = new java.util.HashMap<>();
        ((Map) polluted).put(Integer.valueOf(7), new Contact(EMAIL));
        var source = new Polluted("a-1", polluted);

        assertRefusedAlone(run(source), "SOURCE_MODEL_MAP_OF_RECORD");
        assertThat(prohibited(source)).contains(EMAIL);
    }

    /** A TimeZone whose offset methods throw once armed. */
    static final class ArmedZone extends TimeZone {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean armed;

        private void check() {
            if (armed) {
                calls.incrementAndGet();
                throw new IllegalStateException(EMAIL);
            }
        }

        @Override
        public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
            check();
            return 0;
        }

        @Override
        public void setRawOffset(int offsetMillis) {
        }

        @Override
        public int getRawOffset() {
            check();
            return 0;
        }

        @Override
        public boolean useDaylightTime() {
            return false;
        }

        @Override
        public boolean inDaylightTime(Date date) {
            check();
            return false;
        }
    }

    @Test
    @DisplayName("throwing leaf: a Calendar whose zone throws while the value is written is a read failure")
    void aThrowingLeaf() {
        ArmedZone zone = new ArmedZone();
        Calendar calendar = new GregorianCalendar(zone);
        calendar.set(Calendar.YEAR, 2026);
        zone.armed = true;
        var source = new Dated("a-1", calendar);

        Run run = run(source);

        assertRefusedAlone(run, "SOURCE_VALUE_READ_FAILED");
        assertThat(zone.calls.get()).as("the throw came from inside the leaf reader: once in prohibited, once in scrub").isEqualTo(2);
        assertThat(prohibited(source)).isNotNull();
    }

    @Test
    @DisplayName("an Error from a read: the same outcome as the lazy list")
    void anErrorIsAReadFailure() {
        Run run = run(new Lazy("a-1", lazy(() -> {
            throw new AssertionError("constructed by the test");
        })));

        assertRefusedAlone(run, "SOURCE_VALUE_READ_FAILED");
    }

    @Test
    @DisplayName("a sneaky-thrown checked exception does not escape prohibited or fail the request")
    void aSneakyCheckedException() {
        var source = new Lazy("a-1", lazy(() -> SourceRecordRefusalTest.<RuntimeException>sneaky(new IOException("x"))));

        assertThat(prohibited(source)).isNotNull();
        assertRefusedAlone(run(source), "SOURCE_VALUE_READ_FAILED");
    }

    @Test
    @DisplayName("the interrupt flag is unchanged by a socket timeout or an InterruptedException in a lazy list")
    void theInterruptFlagIsUnchanged() {
        List<Runnable> throwers = List.of(
                () -> {
                    throw new UncheckedIOException(new SocketTimeoutException("upstream timeout"));
                },
                () -> SourceRecordRefusalTest.<RuntimeException>sneaky(new InterruptedException("x")));
        try {
            for (Runnable thrower : throwers) {
                var source = new Lazy("a-1", lazy(thrower));
                assertThat(Thread.currentThread().isInterrupted()).isFalse();

                Run run = run(source);

                assertRefusedAlone(run, "SOURCE_VALUE_READ_FAILED");
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
                catchRefusal(() -> SourceTree.of(source));
                prohibited(source);
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("the walker never clears an interrupt flag that was already set")
    void theInterruptFlagIsNeverCleared() {
        var failing = new Lazy("a-1", lazy(() -> SourceRecordRefusalTest.<RuntimeException>sneaky(
                new InterruptedException("x"))));
        var wellFormed = new Good("b-1", "plain note");
        try {
            Thread.currentThread().interrupt();
            for (Object source : List.of(failing, wellFormed)) {
                catchRefusal(() -> SourceTree.of(source));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                prohibited(source);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            }
        } finally {
            Thread.interrupted();
        }
    }

    private static void catchRefusal(Runnable action) {
        try {
            action.run();
        } catch (PrivacyRefusedException expected) {
            // The refusal itself is asserted elsewhere.
        }
    }

    static final class CyclicException extends JacksonException {
        CyclicException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Test
    @DisplayName("a cyclic cause chain is walked to a bound: the source is refused, nothing hangs")
    void aCyclicCauseChain() {
        var source = new Lazy("a-1", lazy(() -> {
            RuntimeException x = new RuntimeException("x");
            RuntimeException y = new RuntimeException("y");
            x.initCause(y);
            y.initCause(x);
            throw new CyclicException("cyclic", x);
        }));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThat(prohibited(source)).isNotNull();
            assertRefusedAlone(run(source), "SOURCE_VALUE_READ_FAILED");
            try {
                SourceTree.of(source);
            } catch (PrivacyRefusedException refused) {
                assertThat(refused.code()).isEqualTo("SOURCE_VALUE_READ_FAILED");
            }
        });
    }

    @Test
    @DisplayName("the refusal is logged once at ERROR with the code and the throwable's class, and no throwable or message")
    void theRefusalIsLoggedOnceWithoutTheThrowable() {
        Logger logger = (Logger) LoggerFactory.getLogger("io.github.aindriub.dataprism.core.engine.SourceRecordWalker");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            run(new Lazy("a-1", lazy(() -> {
                throw new IllegalStateException(EMAIL);
            })));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("SOURCE_VALUE_READ_FAILED")
                .contains("java.lang.IllegalStateException").doesNotContain(EMAIL);
        assertThat(event.getThrowableProxy()).isNull();
    }

    // ---- the eight holder subclasses, beside a well-formed source ----------------------------------

    private void hold(AtomicBoolean entered) {
        entered.set(true);
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    final class StampedSub extends java.util.concurrent.atomic.AtomicStampedReference<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        StampedSub(Object v) {
            super(v, 0);
        }

        @Override
        public Object getReference() {
            hold(entered);
            return null;
        }
    }

    final class MarkableSub extends java.util.concurrent.atomic.AtomicMarkableReference<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        MarkableSub(Object v) {
            super(v, false);
        }

        @Override
        public Object getReference() {
            hold(entered);
            return null;
        }
    }

    final class ResultFuture extends CompletableFuture<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        @Override
        public Object resultNow() {
            hold(entered);
            return null;
        }
    }

    final class TaskSub extends java.util.concurrent.FutureTask<Object> {
        final AtomicBoolean entered = new AtomicBoolean();
        volatile boolean armed;

        TaskSub(Object v) {
            super(() -> v);
        }

        @Override
        public State state() {
            if (armed) {
                hold(entered);
            }
            return super.state();
        }
    }

    final class RawResultTask extends java.util.concurrent.ForkJoinTask<Object> {
        final AtomicBoolean entered = new AtomicBoolean();
        volatile boolean armed;

        @Override
        public Object getRawResult() {
            if (armed) {
                hold(entered);
            }
            return null;
        }

        @Override
        protected void setRawResult(Object value) {
        }

        @Override
        protected boolean exec() {
            return true;
        }
    }

    @Test
    @DisplayName("each holder subclass at an Object position refuses its source alone and is never entered")
    void holderSubclassesRefuseTheirSourceAlone() {
        Contact record = new Contact(EMAIL);
        StampedSub stamped = new StampedSub(record);
        MarkableSub markable = new MarkableSub(record);
        StateFuture byState = new StateFuture();
        byState.complete(record);
        ResultFuture byResult = new ResultFuture();
        byResult.complete(record);
        TaskSub task = new TaskSub(record);
        task.run();
        task.armed = true;
        RawResultTask fork = new RawResultTask();
        fork.invoke();
        fork.armed = true;
        java.util.concurrent.atomic.AtomicReference<Object> reference =
                new java.util.concurrent.atomic.AtomicReference<>(record) {
                };
        java.util.concurrent.atomic.AtomicReferenceArray<Object> array =
                new java.util.concurrent.atomic.AtomicReferenceArray<>(new Object[]{record}) {
                };
        List<AtomicBoolean> flags = List.of(stamped.entered, markable.entered, byState.entered, byResult.entered,
                task.entered, fork.entered);
        for (Object holder : List.of(stamped, markable, byState, byResult, task, fork, reference, array)) {
            var source = new Holder("a-1", holder);
            Run run = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> run(source));
            assertRefusedAlone(run, "SOURCE_MODEL_NOT_A_RECORD");
            assertThat(prohibited(source)).as(holder.getClass().getName()).doesNotContain(EMAIL);
        }
        assertThat(flags).allSatisfy(flag -> assertThat(flag).isFalse());
    }
}
