package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;

/** Every resolver call and every read is charged before it is made (OD-08-15 (a)). */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowBudgetTest {

    private static final List<String> KEYS = List.of("K-1", "K-2", "K-3");

    /** Delegates to a real budget, noting each reservation in the rig's event list. */
    private static final class NotingBudget implements ScopeBudget {
        final InMemoryScopeBudget real = new InMemoryScopeBudget();
        final FollowRig rig;
        final List<String> reserved = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int grantAtMost = Integer.MAX_VALUE;

        NotingBudget(FollowRig rig) {
            this.rig = rig;
        }

        @Override
        public boolean tryRead(String scopeId, String subjectId, int budget) {
            return real.tryRead(scopeId, subjectId, budget);
        }

        @Override
        public int reads(String scopeId, String subjectId) {
            return real.reads(scopeId, subjectId);
        }

        @Override
        public void forget(String scopeId) {
            real.forget(scopeId);
        }

        @Override
        public ReadReservation reserve(String scopeId, String subjectId, int reads, int subjectBudget, int total) {
            rig.events.add("reserve:" + reads);
            reserved.add(subjectId + "x" + reads);
            if (reserved.size() > grantAtMost) {
                return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SCOPE_TOTAL);
            }
            return real.reserve(scopeId, subjectId, reads, subjectBudget, total);
        }

        @Override
        public void release(ReadReservation reservation, int unused) {
            real.release(reservation, unused);
        }

        @Override
        public int scopeReads(String scopeId) {
            return real.scopeReads(scopeId);
        }
    }

    private static FollowRig translated(NotingBudget[] holder, int budgetCost) {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("unused"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                        key -> new KeyResolver.Resolution(KEYS, false));
        for (String key : KEYS) {
            rig.records.put(key, new LinkBuilderTest.Plain("note"));
        }
        holder[0] = new NotingBudget(rig);
        rig.budget = holder[0];
        rig.budgetCost = budgetCost;
        return rig;
    }

    private static String link(FollowRig rig) {
        return rig.translated("depot-api", "parcel-ref", "parcel-to-depot", "P-1");
    }

    @Test
    void theResolverCallIsChargedItsCostBeforeItIsMadeAndEachReadBeforeItsFetch() {
        NotingBudget[] budget = new NotingBudget[1];
        FollowRig rig = translated(budget, 2);

        rig.follow(link(rig));

        assertThat(rig.events).startsWith("reserve:2", "resolve")
                .containsSubsequence("reserve:1", "fetch", "reserve:1", "fetch", "reserve:1", "fetch");
        assertThat(rig.events.stream().filter(e -> e.equals("reserve:1")).count()).isEqualTo(3);
    }

    @Test
    void eachReadIsChargedToTheReadKeysOwnCounterAndToTheScopeTotal() {
        NotingBudget[] budget = new NotingBudget[1];
        FollowRig rig = translated(budget, 1);

        rig.follow(link(rig));

        for (String key : KEYS) {
            assertThat(budget[0].reads(SCOPE, key)).as(key).isEqualTo(1);
        }
        assertThat(budget[0].reads(SCOPE, "P-1")).isEqualTo(1); // the resolver call, on the chained key
        assertThat(budget[0].scopeReads(SCOPE)).isEqualTo(4);
    }

    @Test
    void aDirectReadIsChargedToTheKeysOwnCounterAndTheScopeTotal() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        NotingBudget budget = new NotingBudget(rig);
        rig.budget = budget;

        rig.follow(rig.direct("parcel-api", "parcel-ref", "P-1"));

        assertThat(budget.reads(SCOPE, "P-1")).isEqualTo(1);
        assertThat(budget.scopeReads(SCOPE)).isEqualTo(1);
        assertThat(rig.events).containsExactly("reserve:1", "fetch");
    }

    @Test
    void anUnaffordableResolverCallIsNotMadeAndRefusesTheCall() {
        NotingBudget[] budget = new NotingBudget[1];
        FollowRig rig = translated(budget, 1);
        budget[0].grantAtMost = 0;

        PrivacyRefusedException refused = FollowTest.refusal(rig, link(rig));

        assertThat(refused.code()).isEqualTo("SCOPE_READ_BUDGET");
        assertThat(rig.resolverCalls).isEmpty();
        assertThat(rig.fetches.get()).isZero();
        assertThat(rig.lastAudit().policyDecision()).isEqualTo("DENY:SCOPE_READ_BUDGET");
    }

    @Test
    void exhaustedBeforeTheFirstReadRefusesTheCallWithScopeReadBudget() {
        NotingBudget[] budget = new NotingBudget[1];
        FollowRig rig = translated(budget, 1);
        budget[0].grantAtMost = 1; // the resolver call only

        PrivacyRefusedException refused = FollowTest.refusal(rig, link(rig));

        assertThat(refused.code()).isEqualTo("SCOPE_READ_BUDGET");
        assertThat(rig.resolverCalls).hasSize(1);
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void exhaustedPartWayReturnsTheItemsReadAndTruncated() {
        NotingBudget[] budget = new NotingBudget[1];
        FollowRig rig = translated(budget, 1);
        budget[0].grantAtMost = 3; // the resolver call and two reads

        SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

        assertThat(rig.fetches.get()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("ANSWERED");
        assertThat(result.items()).hasSize(2);
        assertThat(result.itemsTruncated()).isTrue();
    }

    @Test
    void theSubjectBudgetAndTheScopeTotalBothRefuseARealBudget() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        RequestLimits d = RequestLimits.DEFAULT;
        rig.limits = new RequestLimits(d.maxSources(), d.maxRecords(), d.maxResponseBytes(), d.perSourceTimeout(),
                d.maxConcurrency(), 1, d.requestTimeout(), d.scopeTotalReadBudget());
        String link = rig.direct("parcel-api", "parcel-ref", "P-1");

        rig.follow(link);
        PrivacyRefusedException refused = FollowTest.refusal(rig, link);

        assertThat(refused.code()).isEqualTo("SCOPE_READ_BUDGET");
        assertThat(rig.fetches.get()).isEqualTo(1);
    }
}
