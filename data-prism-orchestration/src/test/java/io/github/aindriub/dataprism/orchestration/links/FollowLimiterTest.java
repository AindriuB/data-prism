package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** One row per gate outcome, through a stub limiter and a failing opener; nothing is ever read. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowLimiterTest {

    /** A limiter with scripted answers; counts its calls. */
    private static final class StubLimiter implements LinkAttemptLimiter {
        final AtomicInteger lockCalls = new AtomicInteger();
        final AtomicInteger recordCalls = new AtomicInteger();
        Supplier<LockState> lock = () -> LockState.UNLOCKED;
        Supplier<Admission> record = () -> Admission.WITHIN_LIMIT;

        @Override
        public LockState lockState(AttemptSubject subject) {
            lockCalls.incrementAndGet();
            return lock.get();
        }

        @Override
        public Admission recordFailure(AttemptSubject subject) {
            recordCalls.incrementAndGet();
            return record.get();
        }
    }

    private static FollowRig rig(StubLimiter limiter) {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        rig.limiter = limiter;
        return rig;
    }

    private static final String FORGED = "AQ" + "A".repeat(80);

    private static void assertRefusal(FollowRig rig, String link, String code, String audit) {
        PrivacyRefusedException refused = FollowTest.refusal(rig, link);

        assertThat(refused.code()).as(audit).isEqualTo(code);
        assertThat(rig.lastAudit().policyDecision()).as(audit).isEqualTo("DENY:" + code);
        assertThat(rig.lastAudit().fieldDispositions()).as(audit).containsEntry("link", audit);
        assertThat(rig.fetches.get()).as(audit + " reads nothing").isZero();
    }

    @Test
    void lockedRefusesEvenAValidLink() {
        StubLimiter limiter = new StubLimiter();
        limiter.lock = () -> LinkAttemptLimiter.LockState.LOCKED;
        FollowRig rig = rig(limiter);

        assertRefusal(rig, rig.direct("parcel-api", "parcel-ref", "P-1"), "LINK_ATTEMPTS_EXHAUSTED",
                "REFUSED:LINK_ATTEMPTS_LOCKED");
    }

    @Test
    void overLimitRefusesAForgedLink() {
        StubLimiter limiter = new StubLimiter();
        limiter.record = () -> LinkAttemptLimiter.Admission.OVER_LIMIT;
        FollowRig rig = rig(limiter);

        assertRefusal(rig, FORGED, "LINK_ATTEMPTS_EXHAUSTED", "REFUSED:LINK_ATTEMPTS_OVER_LIMIT");
        assertThat(limiter.recordCalls.get()).isEqualTo(1);
    }

    @Test
    void ceilingRefusesAForgedLink() {
        StubLimiter limiter = new StubLimiter();
        limiter.record = () -> LinkAttemptLimiter.Admission.CEILING;
        FollowRig rig = rig(limiter);

        assertRefusal(rig, FORGED, "LINK_ATTEMPTS_EXHAUSTED", "REFUSED:LINK_ATTEMPTS_CEILING");
    }

    @Test
    void aLimiterOutageIsRetryableWhetherItReportsUnavailableNullOrThrows() {
        Supplier<?>[] lockOutages = {
                () -> LinkAttemptLimiter.LockState.UNAVAILABLE,
                () -> null,
                () -> {
                    throw new IllegalStateException("store down");
                }};
        for (Supplier<?> outage : lockOutages) {
            StubLimiter limiter = new StubLimiter();
            @SuppressWarnings("unchecked")
            Supplier<LinkAttemptLimiter.LockState> lock = (Supplier<LinkAttemptLimiter.LockState>) outage;
            limiter.lock = lock;
            FollowRig rig = rig(limiter);

            assertRefusal(rig, rig.direct("parcel-api", "parcel-ref", "P-1"), "LINK_CHECK_UNAVAILABLE",
                    "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE");
        }
        Supplier<?>[] recordOutages = {
                () -> LinkAttemptLimiter.Admission.UNAVAILABLE,
                () -> null,
                () -> {
                    throw new IllegalStateException("store down");
                }};
        for (Supplier<?> outage : recordOutages) {
            StubLimiter limiter = new StubLimiter();
            @SuppressWarnings("unchecked")
            Supplier<LinkAttemptLimiter.Admission> record = (Supplier<LinkAttemptLimiter.Admission>) outage;
            limiter.record = record;
            FollowRig rig = rig(limiter);

            assertRefusal(rig, FORGED, "LINK_CHECK_UNAVAILABLE", "REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE");
        }
    }

    @Test
    void aFailureToOpenIsAnsweredLikeAForgedLinkButAuditedAsOpenFailed() {
        StubLimiter limiter = new StubLimiter();
        FollowRig rig = rig(limiter);
        String link = rig.direct("parcel-api", "parcel-ref", "P-1");
        PrivacyRefusedException forged = FollowTest.refusal(rig, FORGED);
        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_AUTH_FAILED");
        rig.clockFails = true; // the opener throws

        assertRefusal(rig, link, "LINK_INVALID", "REFUSED:LINK_ATTEMPTS_OPEN_FAILED");

        PrivacyRefusedException opened = FollowTest.refusal(rig, link);
        assertThat(opened.code()).isEqualTo(forged.code());
        assertThat(opened.path()).isEqualTo(forged.path());
        assertThat(opened.getMessage()).isEqualTo(forged.getMessage());
    }

    @Test
    void anEmptyLinkIsInvalidMalformedAndLeavesTheCounterUntouched() {
        StubLimiter limiter = new StubLimiter();
        FollowRig rig = rig(limiter);

        assertRefusal(rig, "", "LINK_INVALID", "REFUSED:LINK_MALFORMED");

        assertThat(limiter.lockCalls.get()).isZero();
        assertThat(limiter.recordCalls.get()).isZero();
    }

    @Test
    void aForgedLinkIsCountedOnce() {
        StubLimiter limiter = new StubLimiter();
        FollowRig rig = rig(limiter);

        assertRefusal(rig, FORGED, "LINK_INVALID", "REFUSED:LINK_AUTH_FAILED");

        assertThat(limiter.recordCalls.get()).isEqualTo(1);
    }
}
