package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The size and record limits are measured on the response that leaves, with its real label, items and
 * truncation flag, and are exact at the limit (task 252, review round 1).
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowSizeTest {

    private static RequestLimits limits(int records, int bytes) {
        return new RequestLimits(8, records, bytes, Duration.ofSeconds(3), 4, 100, Duration.ofSeconds(10), 50_000);
    }

    private static FollowRig direct() {
        return new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
    }

    private static String directLink(FollowRig rig) {
        return rig.direct("parcel-api", "parcel-ref", "P-1");
    }

    /** Three keys, one with no record (a status-only item), and more known to the resolver (truncated). */
    private static FollowRig translated() {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                        key -> new KeyResolver.Resolution(List.of("K-1", "K-2", "K-3"), true));
        rig.records.put("K-1", new LinkBuilderTest.Plain("one"));
        rig.records.put("K-2", new LinkBuilderTest.Plain("two"));
        return rig;
    }

    private static String translatedLink(FollowRig rig) {
        return rig.translated("depot-api", "parcel-ref", "parcel-to-depot", "P-1");
    }

    private static long bytesOf(ContextResponse response) {
        return LinkBuilderTest.MAPPER.writeValueAsBytes(response).length;
    }

    private static void assertExactBytes(Supplier<FollowRig> build, Function<FollowRig, String> link) {
        FollowRig probe = build.get();
        long exact = bytesOf(probe.follow(link.apply(probe)));

        FollowRig atLimit = build.get();
        atLimit.limits = limits(500, (int) exact);
        assertThat(atLimit.follow(link.apply(atLimit))).isNotNull();

        FollowRig under = build.get();
        under.limits = limits(500, (int) exact - 1);
        PrivacyRefusedException refused = FollowTest.refusal(under, link.apply(under));
        assertThat(refused.code()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(under.lastAudit().policyDecision()).isEqualTo("DENY:RESPONSE_TOO_LARGE");
    }

    @Test
    void aDirectResponseIsRefusedOneByteUnderItsExactSize() {
        assertExactBytes(FollowSizeTest::direct, FollowSizeTest::directLink);
    }

    @Test
    void aTranslatedResponseWithAStatusOnlyItemAndTheTruncationFlagIsRefusedOneByteUnderItsExactSize() {
        assertExactBytes(FollowSizeTest::translated, FollowSizeTest::translatedLink);
    }

    @Test
    void theRecordLimitIsExactForADirectAndATranslatedResponse() {
        FollowRig atOne = direct();
        atOne.limits = limits(1, 512 * 1024);
        assertThat(atOne.follow(directLink(atOne))).isNotNull();

        FollowRig two = translated();
        two.limits = limits(2, 512 * 1024);
        assertThat(two.follow(translatedLink(two))).isNotNull();

        FollowRig one = translated();
        one.limits = limits(1, 512 * 1024);
        PrivacyRefusedException refused = FollowTest.refusal(one, translatedLink(one));
        assertThat(refused.code()).isEqualTo("TOO_MANY_RECORDS");
        assertThat(one.lastAudit().policyDecision()).isEqualTo("DENY:TOO_MANY_RECORDS");
    }
}
