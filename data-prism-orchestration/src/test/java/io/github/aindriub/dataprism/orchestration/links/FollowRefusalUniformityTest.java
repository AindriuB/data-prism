package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.orchestration.AuditedRefusalException;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Every invalid link gets one answer; the real reason goes to the audit only (P4, T7). */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowRefusalUniformityTest {

    private static FollowRig rig() {
        return new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref");
    }

    /** One link per reason of the closed set, each built against the same rig. */
    private static Map<String, Function<FollowRig, String>> linksByReason() {
        Map<String, Function<FollowRig, String>> links = new LinkedHashMap<>();
        links.put("MALFORMED", r -> "");
        links.put("AUTH_FAILED", FollowRig::forged);
        links.put("EXPIRED", r -> {
            String link = r.direct("parcel-api", "parcel-ref", "P-1");
            r.now = NOW.plus(Duration.ofHours(2));
            return link;
        });
        links.put("TARGET_NOT_PERMITTED", r -> r.direct("no-such-api", "parcel-ref", "P-1"));
        links.put("RELATION_UNDECLARED", r -> r.direct("parcel-api", "depot-ref", "P-1"));
        links.put("PAYLOAD_MISMATCH", r -> r.direct("parcel-api", "Not A Namespace", "P-1"));
        return links;
    }

    @Test
    void everyReasonGivesTheSameClientAnswerAndItsOwnAuditReason() {
        PrivacyRefusedException reference = null;
        for (Map.Entry<String, Function<FollowRig, String>> reason : linksByReason().entrySet()) {
            FollowRig rig = rig();
            String link = reason.getValue().apply(rig);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> rig.follow(link));

            assertThat(thrown).as(reason.getKey()).isInstanceOf(AuditedRefusalException.class);
            PrivacyRefusedException refused = (PrivacyRefusedException) thrown;
            assertThat(refused.code()).as(reason.getKey()).isEqualTo("LINK_INVALID");
            assertThat(refused.getMessage()).as(reason.getKey()).contains("The link is not valid.");
            if (reference == null) {
                reference = refused;
            }
            assertThat(refused.code()).isEqualTo(reference.code());
            assertThat(refused.path()).as(reason.getKey()).isEqualTo(reference.path());
            assertThat(refused.getMessage()).as(reason.getKey()).isEqualTo(reference.getMessage());
            assertThat(rig.lastAudit().policyDecision()).as(reason.getKey()).isEqualTo("DENY:LINK_INVALID");
            assertThat(rig.lastAudit().fieldDispositions()).as(reason.getKey())
                    .containsEntry("link", "REFUSED:LINK_" + reason.getKey())
                    .doesNotContainKey("merged:<refused>");
            assertThat(rig.fetches.get()).as(reason.getKey()).isZero();
        }
    }

    @Test
    void theClientTextNamesNoReasonAndNoTarget() {
        FollowRig rig = rig();

        PrivacyRefusedException refused = FollowTest.refusal(rig, rig.direct("no-such-api", "parcel-ref", "P-1"));

        assertThat(refused.getMessage()).doesNotContain("TARGET").doesNotContain("no-such-api")
                .doesNotContain("parcel-ref").doesNotContain("PERMITTED");
    }

    @Test
    void theAuditNamesNoTokenAndNoKey() {
        FollowRig rig = rig();
        String link = rig.direct("no-such-api", "parcel-ref", "P-SECRET-1");

        FollowTest.refusal(rig, link);

        assertThat(rig.audit.toString()).doesNotContain(link).doesNotContain("P-SECRET-1");
    }
}
