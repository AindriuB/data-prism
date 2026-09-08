package io.github.aindriub.dataprism.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeBudgetTest {

    @Test
    @DisplayName("reads are counted per subject within a scope")
    void countsPerSubject() {
        var budget = new ScopeBudget();

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
        var budget = new ScopeBudget();
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
        var budget = new ScopeBudget();
        budget.tryRead("CASE-A", "s-1", 1);

        // If the key were a plain concatenation, a crafted scope id could land on
        // an existing subject's counter and either exhaust or reset it.
        assertThat(budget.tryRead("CASE", "A s-1", 1)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 1)).isFalse();
    }

    @Test
    @DisplayName("the budget holds under concurrent reads")
    void isThreadSafe() throws Exception {
        var budget = new ScopeBudget();
        int permitted = 50;
        List<Callable<Boolean>> attempts = java.util.Collections.nCopies(200,
                () -> budget.tryRead("CASE-A", "s-1", permitted));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long granted = executor.invokeAll(attempts).stream()
                    .map(ScopeBudgetTest::get)
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
        var budget = new ScopeBudget();
        budget.tryRead("CASE-A", "s-1", 5);
        budget.tryRead("CASE-B", "s-1", 5);

        budget.forget("CASE-A");

        assertThat(budget.reads("CASE-A", "s-1")).isZero();
        assertThat(budget.reads("CASE-B", "s-1")).isEqualTo(1);
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
