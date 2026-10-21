package io.github.aindriub.dataprism.core.limits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryScopeBudgetTest {

    @Test
    @DisplayName("reads are counted per subject within a scope")
    void countsPerSubject() {
        var budget = new InMemoryScopeBudget();

        assertThat(budget.tryRead("CASE-A", "s-1", 2)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 2)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 2)).isFalse();

        // A different subject has its own budget; one busy subject must not
        // exhaust the investigation.
        assertThat(budget.tryRead("CASE-A", "s-2", 2)).isTrue();
        // And a different scope is a different investigation entirely.
        assertThat(budget.tryRead("CASE-B", "s-1", 2)).isTrue();
    }

    @Test
    @DisplayName("a refused read is not counted")
    void refusedReadsDoNotAccumulate() {
        var budget = new InMemoryScopeBudget();
        budget.tryRead("CASE-A", "s-1", 1);

        for (int i = 0; i < 5; i++) {
            assertThat(budget.tryRead("CASE-A", "s-1", 1)).isFalse();
        }

        // Otherwise a caller that kept asking would push the count somewhere a
        // later budget increase could never recover from.
        assertThat(budget.reads("CASE-A", "s-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("a scope id containing the separator cannot reach another scope's count")
    void keysCannotBeConfused() {
        var budget = new InMemoryScopeBudget();
        budget.tryRead("CASE-A", "s-1", 1);

        // If the key were a plain concatenation, a crafted scope id could land on
        // an existing subject's counter and either exhaust or reset it.
        assertThat(budget.tryRead("CASE", "A s-1", 1)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 1)).isFalse();
    }

    @Test
    @DisplayName("the budget holds under concurrent reads")
    void isThreadSafe() throws Exception {
        var budget = new InMemoryScopeBudget();
        int permitted = 50;
        List<Callable<Boolean>> attempts = java.util.Collections.nCopies(200,
                () -> budget.tryRead("CASE-A", "s-1", permitted));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long granted = executor.invokeAll(attempts).stream()
                    .map(InMemoryScopeBudgetTest::get)
                    .filter(Boolean::booleanValue)
                    .count();

            // Exactly the budget, not "about" it: a compare-and-set loop that
            // drifted under load would hand out reads nobody authorised.
            assertThat(granted).isEqualTo(permitted);
        }
        assertThat(budget.reads("CASE-A", "s-1")).isEqualTo(permitted);
    }

    @Test
    @DisplayName("forgetting a scope drops its counters and no others")
    void forgetIsScoped() {
        var budget = new InMemoryScopeBudget();
        budget.tryRead("CASE-A", "s-1", 5);
        budget.tryRead("CASE-B", "s-1", 5);

        budget.forget("CASE-A");

        assertThat(budget.reads("CASE-A", "s-1")).isZero();
        assertThat(budget.reads("CASE-B", "s-1")).isEqualTo(1);
    }

    // ---- 0.8 reservations -------------------------------------------------

    @Test
    @DisplayName("subject counter boundary: budget minus one, budget, budget plus one")
    void subjectBoundary() {
        for (int n : new int[] {4, 5, 6}) {
            var budget = new InMemoryScopeBudget();
            var r = budget.reserve("S", "a", n, 5, 100);
            assertThat(r.granted()).isEqualTo(n <= 5);
            if (!r.granted()) {
                assertThat(r.refusedBy()).isEqualTo(ReadReservation.Refusal.SUBJECT);
            }
            assertThat(budget.reads("S", "a")).isEqualTo(n <= 5 ? n : 0);
            assertThat(budget.scopeReads("S")).isEqualTo(n <= 5 ? n : 0);
        }
    }

    @Test
    @DisplayName("scope total boundary: budget minus one, budget, budget plus one; a refusal changes neither counter")
    void scopeBoundary() {
        for (int n : new int[] {9, 10, 11}) {
            var budget = new InMemoryScopeBudget();
            assertThat(budget.reserve("S", "other", 2, 50, 100).granted()).isTrue();
            var r = budget.reserve("S", "a", n - 2, 50, 10);
            boolean fits = 2 + (n - 2) <= 10;
            assertThat(r.granted()).isEqualTo(fits);
            if (!fits) {
                assertThat(r.refusedBy()).isEqualTo(ReadReservation.Refusal.SCOPE_TOTAL);
            }
            assertThat(budget.reads("S", "a")).isEqualTo(fits ? n - 2 : 0);
            assertThat(budget.scopeReads("S")).isEqualTo(fits ? n : 2);
        }
    }

    @Test
    @DisplayName("invalid arguments are rejected before any counter changes")
    void invalidArguments() {
        var budget = new InMemoryScopeBudget();
        assertThatThrownBy(() -> budget.reserve("S", "a", 0, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.reserve("S", "a", -1, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.reserve("S", "a", 1, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.reserve("S", "a", 1, 5, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.reserve("S", "a", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE).granted())
                .isTrue();
        assertThatThrownBy(
                        () -> budget.reserve("S", "a", 1, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.reads("S", "a")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("release returns only what the reservation holds, once, and never below other reservations")
    void releaseIsBounded() {
        var budget = new InMemoryScopeBudget();
        var first = budget.reserve("S", "a", 3, 10, 10);
        var second = budget.reserve("S", "a", 4, 10, 10);

        budget.release(first, 100);
        assertThat(budget.reads("S", "a")).isEqualTo(4);
        assertThat(budget.scopeReads("S")).isEqualTo(4);

        budget.release(first, 1);
        assertThat(budget.reads("S", "a")).isEqualTo(4);

        budget.release(second, 1);
        budget.release(second, 1);
        assertThat(budget.reads("S", "a")).isEqualTo(2);
        assertThat(budget.scopeReads("S")).isEqualTo(2);
    }

    @Test
    @DisplayName("release of a refused reservation, or after forget, does nothing")
    void releaseNoOps() {
        var budget = new InMemoryScopeBudget();
        var refused = budget.reserve("S", "a", 9, 5, 5);
        var held = budget.reserve("S", "a", 2, 5, 5);
        budget.release(refused, 9);
        assertThat(budget.reads("S", "a")).isEqualTo(2);

        budget.forget("S");
        var fresh = budget.reserve("S", "a", 3, 5, 5);
        budget.release(held, 2);

        assertThat(budget.reads("S", "a")).isEqualTo(3);
        assertThat(budget.scopeReads("S")).isEqualTo(3);
        budget.release(fresh, 3);
        assertThat(budget.scopeReads("S")).isZero();
    }

    @Test
    @DisplayName("tryRead counts toward the scope total")
    void tryReadCountsInScope() {
        var budget = new InMemoryScopeBudget();
        budget.tryRead("S", "a", 5);
        budget.tryRead("S", "b", 5);
        assertThat(budget.scopeReads("S")).isEqualTo(2);
    }

    @Test
    @DisplayName("forget drops the scope total with the subject counters")
    void forgetDropsTotal() {
        var budget = new InMemoryScopeBudget();
        budget.reserve("S", "a", 2, 5, 5);
        budget.forget("S");
        assertThat(budget.scopeReads("S")).isZero();
        assertThat(budget.reads("S", "a")).isZero();
    }

    @Test
    @DisplayName("32 threads on one subject and one scope never pass either budget; counts equal granted sum")
    void singleSubjectProperty() throws Exception {
        var budget = new InMemoryScopeBudget();
        var pool = Executors.newFixedThreadPool(32);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < 32; t++) {
                long seed = t;
                tasks.add(() -> {
                    var rnd = new Random(seed);
                    int got = 0;
                    for (int i = 0; i < 200; i++) {
                        int n = 1 + rnd.nextInt(5);
                        if (budget.reserve("S", "a", n, 1000, 1000).granted()) {
                            got += n;
                        }
                    }
                    return got;
                });
            }
            int granted = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                granted += f.get();
            }
            assertThat(granted).isLessThanOrEqualTo(1000);
            assertThat(budget.reads("S", "a")).isEqualTo(granted);
            assertThat(budget.scopeReads("S")).isEqualTo(granted);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("64 subjects, 32 threads, reserve and release: the scope total holds and the books balance")
    void multiSubjectProperty() throws Exception {
        final int total = 500;
        final int subjectBudget = 40; // 64 * 40 far exceeds the scope total
        var budget = new InMemoryScopeBudget();
        var pool = Executors.newFixedThreadPool(33);
        var stop = new AtomicBoolean();
        var maxSeen = new AtomicInteger();
        var subjectOver = new AtomicBoolean();
        try {
            var sampler = pool.submit(() -> {
                while (!stop.get()) {
                    maxSeen.accumulateAndGet(budget.scopeReads("S"), Math::max);
                    for (int s = 0; s < 64; s++) {
                        if (budget.reads("S", "s" + s) > subjectBudget) {
                            subjectOver.set(true);
                        }
                    }
                }
            });
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < 32; t++) {
                long seed = 100 + t;
                tasks.add(() -> {
                    var rnd = new Random(seed);
                    int net = 0;
                    for (int i = 0; i < 2000; i++) {
                        String subject = "s" + rnd.nextInt(64);
                        int n = 1 + rnd.nextInt(6);
                        var r = budget.reserve("S", subject, n, subjectBudget, total);
                        if (r.granted()) {
                            net += n;
                            if (rnd.nextBoolean()) {
                                int give = rnd.nextInt(n + 1);
                                budget.release(r, give);
                                net -= give;
                            }
                        }
                    }
                    return net;
                });
            }
            int net = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                net += f.get();
            }
            stop.set(true);
            sampler.get();

            int sum = 0;
            for (int s = 0; s < 64; s++) {
                int c = budget.reads("S", "s" + s);
                assertThat(c).isBetween(0, subjectBudget);
                sum += c;
            }
            assertThat(maxSeen.get()).isLessThanOrEqualTo(total);
            assertThat(subjectOver).isFalse();
            assertThat(budget.scopeReads("S")).isEqualTo(sum).isEqualTo(net).isLessThanOrEqualTo(total);
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("forget racing reserve never leaves subject reads missing from the scope total")
    void forgetRacingReserve() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 200; round++) {
                var budget = new InMemoryScopeBudget();
                List<Callable<Object>> tasks = new ArrayList<>();
                for (int t = 0; t < 6; t++) {
                    int id = t;
                    tasks.add(() -> {
                        for (int i = 0; i < 20; i++) {
                            budget.reserve("S", "s" + (id + i) % 4, 1, 1000, 1000);
                        }
                        return null;
                    });
                }
                tasks.add(() -> {
                    budget.forget("S");
                    return null;
                });
                pool.invokeAll(tasks).forEach(f -> {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                int sum = 0;
                for (int s = 0; s < 4; s++) {
                    sum += budget.reads("S", "s" + s);
                }
                assertThat(budget.scopeReads("S")).isEqualTo(sum);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("tryRead refuses rather than wrapping the scope total")
    void tryReadDoesNotOverflowTotal() {
        var budget = new InMemoryScopeBudget();
        assertThat(budget.reserve("S", "a", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE).granted())
                .isTrue();

        assertThat(budget.tryRead("S", "b", 5)).isFalse();

        assertThat(budget.scopeReads("S")).isEqualTo(Integer.MAX_VALUE);
        // Never granted: the counter is full, so the sum overflows and is rejected.
        assertThatThrownBy(() -> budget.reserve("S", "c", 5, 5, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null subject is rejected before anything is charged")
    void nullSubjectChargesNothing() {
        var budget = new InMemoryScopeBudget();
        assertThatThrownBy(() -> budget.reserve("T", null, 3, 5, 5)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> budget.reserve(null, "a", 3, 5, 5)).isInstanceOf(NullPointerException.class);
        assertThat(budget.scopeReads("T")).isZero();
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
