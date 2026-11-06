package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.KeyResolutionException;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A follow that returns only a status (no keys, a failed resolver, a timeout before the first read, or no data)
 * is measured against the byte limit like any other response, and a refusal is audited as a denial and gives
 * its reservations back (task 252, review round 5). One finishing step serves every successful exit.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowStatusOnlySizeTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static RequestLimits limits(int bytes) {
        return new RequestLimits(8, 500, bytes, Duration.ofSeconds(3), 4, 100, REQUEST_TIMEOUT, 50_000);
    }

    /** A budget that counts what is given back and can make one reservation outlast the follow. */
    private static final class CountingBudget implements ScopeBudget {
        final InMemoryScopeBudget real = new InMemoryScopeBudget();
        final FollowRig rig;
        final int slowCall;
        int calls;
        int released;

        CountingBudget(FollowRig rig, int slowCall) {
            this.rig = rig;
            this.slowCall = slowCall;
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
            if (++calls == slowCall) {
                rig.now = rig.now.plus(REQUEST_TIMEOUT.plusSeconds(1));
            }
            return real.reserve(scopeId, subjectId, reads, subjectBudget, total);
        }

        @Override
        public void release(ReadReservation reservation, int unused) {
            released += unused;
            real.release(reservation, unused);
        }

        @Override
        public int scopeReads(String scopeId) {
            return real.scopeReads(scopeId);
        }
    }

    private static FollowRig translated(KeyResolver.Resolution resolution, int slowCall) {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("unused"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref", key -> {
                    if (resolution == null) {
                        throw new KeyResolutionException(KeyResolutionException.Reason.UNAVAILABLE);
                    }
                    return resolution;
                });
        rig.budget = new CountingBudget(rig, slowCall);
        return rig;
    }

    private static String link(FollowRig rig) {
        return rig.translated("depot-api", "parcel-ref", "parcel-to-depot", "P-1");
    }

    private static FollowRig noKeys() {
        return translated(new KeyResolver.Resolution(List.of(), false), -1);
    }

    private static FollowRig resolverFailed() {
        return translated(null, -1);
    }

    private static FollowRig timedOutBeforeTheFirstRead() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("unused"), "depot-ref");
        rig.resolver("parcel-to-depot", "parcel-ref", "depot-ref", key -> {
            rig.now = rig.now.plus(REQUEST_TIMEOUT.plusSeconds(1));
            return new KeyResolver.Resolution(List.of("K-1", "K-2"), false);
        });
        rig.budget = new CountingBudget(rig, -1);
        return rig;
    }

    /** The first read's reservation (the second call; the first is the resolver's) takes the rest of the time. */
    private static FollowRig reservationOutlastsTheFollow() {
        FollowRig rig = translated(new KeyResolver.Resolution(List.of("K-1", "K-2"), false), 2);
        rig.records.put("K-1", new LinkBuilderTest.Plain("one"));
        return rig;
    }

    private static FollowRig everyItemNoData() {
        FollowRig rig = translated(new KeyResolver.Resolution(List.of("K-1", "K-2"), false), -1);
        rig.records.put("other", new LinkBuilderTest.Plain("x"));
        return rig;
    }

    private static FollowRig directNoData() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        rig.records.put("other", new LinkBuilderTest.Plain("x"));
        rig.budget = new CountingBudget(rig, -1);
        return rig;
    }

    private static String directLink(FollowRig rig) {
        return rig.direct("parcel-api", "parcel-ref", "P-1");
    }

    private static void assertMeasured(Supplier<FollowRig> build, boolean direct, String status) {
        String target = direct ? "parcel-api" : "depot-api";
        FollowRig probe = build.get();
        probe.limits = limits(RequestLimits.DEFAULT.maxResponseBytes());
        ContextResponse envelope = probe.follow(direct ? directLink(probe) : link(probe));
        assertThat(envelope.sources().get(probe.label(target)).status()).isEqualTo(status);
        int exact = LinkBuilderTest.MAPPER.writeValueAsBytes(envelope).length;
        CountingBudget allowed = (CountingBudget) probe.budget;

        FollowRig atLimit = build.get();
        atLimit.limits = limits(exact);
        assertThat(atLimit.follow(direct ? directLink(atLimit) : link(atLimit))).as(status + " at the limit").isNotNull();
        assertThat(atLimit.lastAudit().policyDecision()).isEqualTo("ALLOW");

        for (int limit : new int[] {1, exact - 1}) {
            FollowRig under = build.get();
            under.limits = limits(limit);
            PrivacyRefusedException refused = FollowTest.refusal(under, direct ? directLink(under) : link(under));
            assertThat(refused.code()).as(status + " under " + limit).isEqualTo("RESPONSE_TOO_LARGE");
            assertThat(under.lastAudit().policyDecision()).isEqualTo("DENY:RESPONSE_TOO_LARGE");
            CountingBudget budget = (CountingBudget) under.budget;
            assertThat(budget.scopeReads(SCOPE)).as("reads charged, as when allowed").isEqualTo(allowed.scopeReads(SCOPE));
            assertThat(budget.released).as("reservations given back, as when allowed").isEqualTo(allowed.released);
        }
    }

    @Test
    void aResolverReturningNoKeysIsMeasured() {
        assertMeasured(FollowStatusOnlySizeTest::noKeys, false, "NO_DATA");
    }

    @Test
    void aFailedResolverIsMeasured() {
        assertMeasured(FollowStatusOnlySizeTest::resolverFailed, false, "RESOLVER_FAILED");
    }

    @Test
    void aTimeoutBeforeTheFirstReadIsMeasured() {
        assertMeasured(FollowStatusOnlySizeTest::timedOutBeforeTheFirstRead, false, "TIMED_OUT");
    }

    @Test
    void aReservationThatOutlastsTheFollowIsMeasuredAndStillGivesItsReservationBack() {
        assertMeasured(FollowStatusOnlySizeTest::reservationOutlastsTheFollow, false, "TIMED_OUT");
        FollowRig rig = reservationOutlastsTheFollow();
        rig.limits = limits(1);
        FollowTest.refusal(rig, link(rig));
        assertThat(((CountingBudget) rig.budget).released).isEqualTo(1);
    }

    @Test
    void everyItemNoDataIsMeasured() {
        assertMeasured(FollowStatusOnlySizeTest::everyItemNoData, false, "NO_DATA");
    }

    @Test
    void aDirectNoDataIsMeasured() {
        assertMeasured(FollowStatusOnlySizeTest::directNoData, true, "NO_DATA");
    }

    /**
     * The guard: a follow path can only hand back a pending result, never a response, so it cannot skip the
     * finishing step. The only methods of the orchestrator that follow and return a response are the entry
     * point and the finishing step.
     */
    @Test
    void onlyTheEntryPointAndTheFinishingStepReturnAFollowResponse() {
        Set<String> names = new TreeSet<>();
        for (Method method : DefaultContextOrchestrator.class.getDeclaredMethods()) {
            if (method.getName().startsWith("follow") && ContextResponse.class.isAssignableFrom(method.getReturnType())) {
                names.add(method.getName());
            }
        }
        assertThat(names).containsExactly("follow", "followFinish");
        assertThat(Arrays.stream(DefaultContextOrchestrator.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("followChecked")).map(m -> m.getReturnType().getSimpleName()))
                .containsExactly("FollowPending");
    }
}
