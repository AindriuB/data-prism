package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link SourceValues#prohibited} is the oracle of the cross-source leak validator, and it runs outside the
 * per-source {@code try}: it throws nothing but a {@code VirtualMachineError}, never calls a method directly
 * on a user class, and never blocks (task 288). All values are invented.
 */
class SourceRecordProhibitedValuesTest {

    private static final String EMAIL = "ada@example.invalid";

    private static final Duration GUARD = Duration.ofSeconds(10);

    record Contact(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @LlmExposedModel
    record Any(@InternalIdentifier String ref, @NonSensitive(reason = "x") Object any) {
    }

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseBlockedThreads() {
        release.countDown();
    }

    private static Set<String> prohibited(Object source) {
        return SourceValues.prohibited(source, new DefaultFieldMetadataResolver());
    }

    private static Set<String> within(Object source) {
        return assertTimeoutPreemptively(GUARD, () -> prohibited(source));
    }

    // ---- values the strict walk refuses --------------------------------------------------------

    @Test
    @DisplayName("every scalar under a record at a position that does not declare it is prohibited")
    void aRefusedRecordIsStillCollected() {
        assertThat(within(new Any("r", new Contact(EMAIL)))).contains(EMAIL);
        assertThat(within(new Any("r", List.of(List.of(new Contact(EMAIL)))))).contains(EMAIL);
    }

    record Envelope<T>(T item) {
    }

    @Test
    @DisplayName("a generic record, a user class and a non-record root are not thrown for")
    void refusalsDoNotEscape() {
        assertThat(within(new Any("r", new Envelope<>(new Contact(EMAIL))))).contains(EMAIL);
        class User {
        }
        assertThat(within(new Any("r", new User()))).doesNotContain(EMAIL);
        assertThat(within("a string root")).isNotNull();
        assertThat(within(Collections.singletonList(EMAIL))).isNotNull();
    }

    // ---- holders on the allowlist, and the exact-class rule ------------------------------------

    /** A holder whose overriding method records the call and blocks until the test's cleanup. */
    private void block(AtomicBoolean entered) {
        entered.set(true);
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("blocked");
    }

    final class StampedSub extends AtomicStampedReference<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        StampedSub(Object value) {
            super(value, 0);
        }

        @Override
        public Object getReference() {
            block(entered);
            return null;
        }
    }

    final class MarkableSub extends AtomicMarkableReference<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        MarkableSub(Object value) {
            super(value, false);
        }

        @Override
        public Object getReference() {
            block(entered);
            return null;
        }
    }

    final class FutureStateSub extends CompletableFuture<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        @Override
        public State state() {
            block(entered);
            return State.SUCCESS;
        }
    }

    final class FutureResultSub extends CompletableFuture<Object> {
        final AtomicBoolean entered = new AtomicBoolean();

        @Override
        public Object resultNow() {
            block(entered);
            return null;
        }
    }

    final class TaskSub extends FutureTask<Object> {
        final AtomicBoolean entered = new AtomicBoolean();
        private volatile boolean armed;

        TaskSub(Object value) {
            super(() -> value);
        }

        void arm() {
            armed = true;
        }

        @Override
        public State state() {
            if (armed) {
                block(entered);
            }
            return super.state();
        }
    }

    static final class ReferenceSub extends AtomicReference<Object> {
        ReferenceSub(Object value) {
            super(value);
        }
    }

    static final class ArraySub extends AtomicReferenceArray<Object> {
        ArraySub(Object value) {
            super(new Object[]{value});
        }
    }

    final class RawResultTask extends ForkJoinTask<Object> {
        final AtomicBoolean entered = new AtomicBoolean();
        private volatile boolean armed;

        void arm() {
            armed = true;
        }

        @Override
        public Object getRawResult() {
            if (armed) {
                block(entered);
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
    @DisplayName("a subclass of a listed holder is prohibited-unknown: none of its methods is called, nothing is collected")
    void subclassesOfListedHoldersAreNeverEntered() {
        Contact record = new Contact(EMAIL);

        StampedSub stamped = new StampedSub(record);
        assertThat(within(new Any("r", stamped))).doesNotContain(EMAIL);
        assertThat(stamped.entered).isFalse();

        MarkableSub markable = new MarkableSub(record);
        assertThat(within(new Any("r", markable))).doesNotContain(EMAIL);
        assertThat(markable.entered).isFalse();

        FutureStateSub byState = new FutureStateSub();
        byState.complete(record);
        assertThat(within(new Any("r", byState))).doesNotContain(EMAIL);
        assertThat(byState.entered).isFalse();

        FutureResultSub byResult = new FutureResultSub();
        byResult.complete(record);
        assertThat(within(new Any("r", byResult))).doesNotContain(EMAIL);
        assertThat(byResult.entered).isFalse();

        TaskSub task = new TaskSub(record);
        task.run();
        task.arm();
        assertThat(within(new Any("r", task))).doesNotContain(EMAIL);
        assertThat(task.entered).isFalse();

        assertThat(within(new Any("r", new ReferenceSub(record)))).doesNotContain(EMAIL);
        assertThat(within(new Any("r", new ArraySub(record)))).doesNotContain(EMAIL);

        RawResultTask fork = new RawResultTask();
        fork.invoke();
        fork.arm();
        assertThat(within(new Any("r", fork))).doesNotContain(EMAIL);
        assertThat(fork.entered).isFalse();
    }

    @Test
    @DisplayName("the listed holders of exactly the listed classes put the record's email in the set")
    void listedHoldersAreRead() {
        Contact record = new Contact(EMAIL);
        CompletableFuture<Object> done = CompletableFuture.completedFuture(record);
        FutureTask<Object> task = new FutureTask<>(() -> record);
        task.run();
        for (Object holder : List.of(new AtomicReference<>(record), new AtomicReferenceArray<>(new Object[]{record}),
                new AtomicStampedReference<>(record, 1), new AtomicMarkableReference<>(record, true),
                Optional.of(record), done, task)) {
            assertThat(within(new Any("r", holder))).as(holder.getClass().getName()).contains(EMAIL);
        }
    }

    // ---- futures and other holders -------------------------------------------------------------

    /** A user class implementing Future, whose isDone() says true and whose get() blocks. */
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
        public Object get() {
            block(entered);
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
            block(entered);
            return null;
        }
    }

    @Test
    @DisplayName("an unfinished, cancelled or failed CompletableFuture is not read, and nothing is thrown or waited for")
    void unfinishedFuturesAreNotRead() {
        CompletableFuture<Object> unfinished = new CompletableFuture<>();
        CompletableFuture<Object> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException(EMAIL));
        for (CompletableFuture<Object> future : List.of(unfinished, cancelled, failed)) {
            assertThat(within(new Any("r", future))).doesNotContain(EMAIL);
        }
    }

    @Test
    @DisplayName("a ThreadLocal is not read, so a withInitial supplier is never run")
    void aThreadLocalIsNotRead() {
        AtomicInteger calls = new AtomicInteger();
        ThreadLocal<Object> local = ThreadLocal.withInitial(() -> {
            calls.incrementAndGet();
            return new Contact(EMAIL);
        });
        assertThat(within(new Any("r", local))).doesNotContain(EMAIL);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a user class implementing Future is never called, neither isDone() nor get()")
    void aUserFutureIsNeverCalled() {
        UserFuture future = new UserFuture();
        assertThat(within(new Any("r", future))).doesNotContain(EMAIL);
        assertThat(future.entered).isFalse();
    }

    @Test
    @DisplayName("a Stream holder is not consumed")
    void aStreamIsNotConsumed() {
        AtomicInteger advances = new AtomicInteger();
        Stream<String> stream = StreamSupport.stream(new Spliterators.AbstractSpliterator<String>(1, 0) {
            @Override
            public boolean tryAdvance(Consumer<? super String> action) {
                advances.incrementAndGet();
                return false;
            }
        }, false);
        assertThat(within(new Any("r", stream))).doesNotContain(EMAIL);
        assertThat(advances).hasValue(0);
        // The stream is still usable by a later reader.
        assertThat(stream.toList()).isEmpty();
        assertThat(advances.get()).isPositive();
    }

    // ---- read failures -------------------------------------------------------------------------

    @LlmExposedModel
    record LazyThenSecret(@NonSensitive(reason = "x") List<String> lazy,
                          @SensitiveData(classifications = DataClassification.CONTACT,
                                  namespace = PrivacyNamespace.EMAIL,
                                  suggestedAction = PrivacyAction.REDACT) String secret) {
    }

    /** A list whose iterator() throws what the supplier makes. */
    static final class ThrowingList extends AbstractList<String> {
        private final Runnable thrower;

        ThrowingList(Runnable thrower) {
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

    @Test
    @DisplayName("the walk continues past a position that failed: the next sibling is still collected")
    void aFailedPositionDoesNotStopTheWalk() {
        var lazy = Collections.unmodifiableList(new ThrowingList(() -> {
            throw new IllegalStateException(EMAIL);
        }));
        assertThat(within(new LazyThenSecret(lazy, EMAIL))).contains(EMAIL);
    }

    @Test
    @DisplayName("a VirtualMachineError is not caught: the process, not the source, is at fault")
    void aVirtualMachineErrorPropagates() {
        var lazy = Collections.unmodifiableList(new ThrowingList(() -> {
            throw new OutOfMemoryError("constructed by the test");
        }));
        var source = new LazyThenSecret(lazy, EMAIL);
        assertThatThrownBy(() -> SourceTree.of(source)).isInstanceOf(OutOfMemoryError.class);
        assertThatThrownBy(() -> prohibited(source)).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("a read failure refuses with a fixed message that carries neither the exception's message nor its cause")
    void aReadFailureCarriesNoValue() {
        var lazy = Collections.unmodifiableList(new ThrowingList(() -> {
            throw new IllegalStateException(EMAIL, new IllegalArgumentException(EMAIL));
        }));
        assertThatThrownBy(() -> SourceTree.of(new LazyThenSecret(lazy, EMAIL)))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("SOURCE_VALUE_READ_FAILED");
                    assertThat(e.path()).isEqualTo("$.lazy").doesNotContain(EMAIL);
                    assertThat(e.getMessage()).endsWith("a value could not be read").doesNotContain(EMAIL);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("an Error other than a VirtualMachineError is a read failure, not a crash")
    void anAssertionErrorIsAReadFailure() {
        var lazy = Collections.unmodifiableList(new ThrowingList(() -> {
            throw new AssertionError("constructed by the test");
        }));
        assertThatThrownBy(() -> SourceTree.of(new LazyThenSecret(lazy, EMAIL)))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("SOURCE_VALUE_READ_FAILED"));
        assertThat(within(new LazyThenSecret(lazy, EMAIL))).contains(EMAIL);
    }

    // ---- review fixes ----------------------------------------------------------------------------

    @LlmExposedModel
    record Photo(@InternalIdentifier String id,
                 @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                         suggestedAction = PrivacyAction.REDACT) byte[] photo) {
    }

    @Test
    @DisplayName("a sensitive byte[] field puts its base64 text in the set and does not throw")
    void aByteArrayFieldIsCollected() {
        assertThat(within(new Photo("1", new byte[]{1, 2, 3}))).contains("AQID");
        tools.jackson.databind.node.ObjectNode node = SourceTree.newObject();
        node.put("photo", new byte[]{1, 2, 3});
        assertThat(SourceValues.prohibited(node, new io.github.aindriub.dataprism.core.spi.FieldMetadataResolver() {
            @Override
            public List<io.github.aindriub.dataprism.core.model.FieldMetadata> resolve(Class<?> type) {
                return new DefaultFieldMetadataResolver().resolve(Photo.class);
            }
        })).contains("AQID");
    }

    /** An exception whose getCause() throws an Error. */
    static final class BadCause extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            throw new AssertionError("getCause");
        }
    }

    @Test
    @DisplayName("a getCause() that throws an Error is a read failure: of refuses, prohibited keeps the sibling")
    void aThrowingGetCauseIsAReadFailure() {
        var lazy = Collections.unmodifiableList(new ThrowingList(() -> {
            throw new BadCause();
        }));
        var source = new LazyThenSecret(lazy, EMAIL);
        assertThatThrownBy(() -> SourceTree.of(source)).isInstanceOfSatisfying(PrivacyRefusedException.class,
                e -> assertThat(e.code()).isEqualTo("SOURCE_VALUE_READ_FAILED"));
        assertThat(within(source)).contains(EMAIL);
    }

    private static Object nestedOptionals(int levels) {
        Object value = "x";
        for (int i = 0; i < levels; i++) {
            value = Optional.of(value);
        }
        return value;
    }

    @Test
    @DisplayName("200,000 nested Optionals are TOO_DEEP in of and do not overflow the lenient walk")
    void nestedOptionalsAreBounded() {
        var source = new Any("r", nestedOptionals(200_000));
        assertThatThrownBy(() -> SourceTree.of(source)).isInstanceOfSatisfying(PrivacyRefusedException.class,
                e -> assertThat(e.code()).isEqualTo("TOO_DEEP"));
        assertThat(within(source)).isNotNull();
    }

    @LlmExposedModel
    record WithNode(@InternalIdentifier String id,
                    @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                            suggestedAction = PrivacyAction.REDACT) tools.jackson.databind.JsonNode n) {
    }

    @Test
    @DisplayName("a JsonNode nested past the bound keeps its shallow scalars in the lenient walk")
    void aDeepNodeKeepsItsShallowScalars() {
        tools.jackson.databind.node.ObjectNode root = SourceTree.newObject();
        root.put("email", EMAIL);
        tools.jackson.databind.node.ObjectNode cursor = root.putObject("d");
        for (int i = 0; i < 70; i++) {
            cursor = cursor.putObject("d");
        }
        assertThat(within(new WithNode("1", root))).contains(EMAIL);
    }
}
