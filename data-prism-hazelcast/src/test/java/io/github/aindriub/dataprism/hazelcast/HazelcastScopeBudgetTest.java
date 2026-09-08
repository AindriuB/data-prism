package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class HazelcastScopeBudgetTest {

    private PrivacyCluster cluster;

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private HazelcastScopeBudget budget() {
        Config config = new Config();
        config.setClusterName("dataprism-budget-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, false);
        return new HazelcastScopeBudget(cluster);
    }

    @Test
    @DisplayName("the budget is shared, so the number means what it says")
    void countsAreShared() {
        var budget = budget();

        assertThat(budget.tryRead("CASE-A", "s-1", 2)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 2)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 2)).isFalse();

        // A second reader against the same cluster sees the same count, which is
        // the whole difference from the in-memory implementation: there, eight
        // instances would mean eight times the budget.
        assertThat(new HazelcastScopeBudget(cluster).reads("CASE-A", "s-1")).isEqualTo(2);
        assertThat(new HazelcastScopeBudget(cluster).tryRead("CASE-A", "s-1", 2)).isFalse();
    }

    @Test
    @DisplayName("concurrent readers cannot exceed the budget between them")
    void concurrentReadsRespectTheBudget() throws Exception {
        var budget = budget();
        int permitted = 25;
        List<Callable<Boolean>> attempts =
                Collections.nCopies(120, () -> budget.tryRead("CASE-A", "s-1", permitted));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long granted = executor.invokeAll(attempts).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).filter(Boolean::booleanValue).count();

            // Exactly, not approximately: a lock that drifted under contention
            // would hand out reads nobody authorised, and the setting would
            // quietly mean something other than it says.
            assertThat(granted).isEqualTo(permitted);
        }
    }

    @Test
    @DisplayName("an unreachable cluster refuses the read rather than leaving it uncounted")
    void failsClosedWhenTheClusterIsGone() {
        var budget = budget();
        assertThat(budget.tryRead("CASE-A", "s-1", 100)).isTrue();

        cluster.close();

        // Deliberately the opposite of the identity cache, which falls back to
        // computation. An uncounted read is not a degraded answer, it is no limit
        // at all on the one control over how much a caller can extract about one
        // subject — and it would fail silently.
        assertThat(budget.tryRead("CASE-A", "s-1", 100)).isFalse();
    }

    @Test
    @DisplayName("different subjects and scopes have their own budgets")
    void budgetsAreScoped() {
        var budget = budget();
        budget.tryRead("CASE-A", "s-1", 1);

        assertThat(budget.tryRead("CASE-A", "s-2", 1)).isTrue();
        assertThat(budget.tryRead("CASE-B", "s-1", 1)).isTrue();
        assertThat(budget.tryRead("CASE-A", "s-1", 1)).isFalse();
    }
}
