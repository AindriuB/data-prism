package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.KeyResolutionException;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.orchestration.links.FollowTest.FollowRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;

/** A translated link: the resolver at follow time, then one read per key, in a fixed order (OD-R2, OD-9). */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowTranslatedTest {

    private static final List<String> KEYS = List.of("K-1", "K-2", "K-3", "K-4");

    private static FollowRig rig(List<String> resolved, boolean more) {
        FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("unused"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                        key -> new KeyResolver.Resolution(resolved, more));
        for (int i = 0; i < KEYS.size(); i++) {
            rig.records.put(KEYS.get(i), new LinkBuilderTest.Plain("note-" + i));
        }
        return rig;
    }

    private static String link(FollowRig rig) {
        return rig.translated("depot-api", "parcel-ref", "parcel-to-depot", "P-1");
    }

    private static List<String> byFingerprint(FollowRig rig, List<String> keys) {
        var scopeKeys = rig.keys().forScope(SCOPE);
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparing(k -> scopeKeys.fingerprint(k.getBytes(StandardCharsets.UTF_8)),
                Arrays::compareUnsigned));
        return sorted;
    }

    @Test
    void theResolverIsCalledOnceWithTheCapAndEachKeyIsReadOnceInFingerprintOrder() {
        FollowRig rig = rig(KEYS, false);

        SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

        assertThat(rig.resolverCalls).containsExactly("parcel-to-depot");
        assertThat(rig.resolverMax).containsExactly(5);
        assertThat(rig.requests).extracting(r -> r.subjectId()).containsExactlyElementsOf(byFingerprint(rig, KEYS));
        assertThat(rig.requests).allSatisfy(r -> assertThat(r.keyNamespace().orElseThrow().value()).isEqualTo("depot-ref"));
        assertThat(result.status()).isEqualTo("ANSWERED");
        assertThat(result.entity()).isNull();
        assertThat(result.itemsTruncated()).isNull();
        List<String> expectedNotes = byFingerprint(rig, KEYS).stream().map(k -> "note-" + KEYS.indexOf(k)).toList();
        assertThat(result.items()).extracting(i -> i.entity().get("note").asString())
                .containsExactlyElementsOf(expectedNotes);
    }

    @Test
    void theOrderDoesNotDependOnTheOrderTheResolverUsed() {
        FollowRig forward = rig(KEYS, false);
        FollowRig reverse = rig(List.of("K-4", "K-3", "K-2", "K-1"), false);

        forward.follow(link(forward));
        reverse.follow(link(reverse));

        assertThat(reverse.requests).extracting(r -> r.subjectId())
                .containsExactlyElementsOf(forward.requests.stream().map(r -> r.subjectId()).toList());
    }

    @Test
    void aDuplicateKeyIsReadOnce() {
        FollowRig rig = rig(List.of("K-1", "K-1", "K-2"), false);

        SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

        assertThat(rig.fetches.get()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void moreFromTheResolverSetsTruncatedAndFiveKeysWithoutMoreDoNot() {
        FollowRig more = rig(KEYS.subList(0, 2), true);
        FollowRig exact = rig(KEYS.subList(0, 2), false);

        assertThat(more.follow(link(more)).sources().get(more.label("depot-api")).itemsTruncated()).isTrue();
        assertThat(exact.follow(link(exact)).sources().get(exact.label("depot-api")).itemsTruncated()).isNull();
    }

    @Test
    void moreThanTheCapIsResolverFailedWithNoRead() {
        FollowRig rig = rig(List.of("K-1", "K-2", "K-3", "K-4", "K-5", "K-6"), false);

        SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

        assertThat(result.status()).isEqualTo("RESOLVER_FAILED");
        assertThat(result.entity()).isNull();
        assertThat(result.items()).isEmpty();
        assertThat(rig.fetches.get()).isZero();
        assertThat(rig.count(Metric.RESOLVER_TOO_MANY)).isEqualTo(1);
    }

    @Test
    void moreWithNoKeysIsInvalidOutput() {
        FollowRig rig = rig(List.of(), true);

        SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

        assertThat(result.status()).isEqualTo("RESOLVER_FAILED");
        assertThat(rig.count(Metric.RESOLVER_INVALID_OUTPUT)).isEqualTo(1);
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void anEmptyResultIsNoData() {
        FollowRig rig = rig(List.of(), false);

        SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(rig.fetches.get()).isZero();
        assertThat(rig.lastAudit().policyDecision()).isEqualTo("ALLOW");
    }

    @Test
    void eachFailureReasonIsResolverFailedWithItsMetricAndNeverFallsBack() {
        for (KeyResolutionException.Reason reason : KeyResolutionException.Reason.values()) {
            FollowRig rig = new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                    .source("depot-api", new LinkBuilderTest.Plain("unused"), "depot-ref")
                    .source("other-api", new LinkBuilderTest.Plain("other"), "route-ref")
                    .resolver("parcel-to-depot", "parcel-ref", "depot-ref", key -> {
                        throw new KeyResolutionException(reason);
                    })
                    .resolver("parcel-to-route", "parcel-ref", "route-ref",
                            key -> new KeyResolver.Resolution(List.of("R-1"), false));

            SourceResult result = rig.follow(link(rig)).sources().get(rig.label("depot-api"));

            assertThat(result.status()).as(reason.name()).isEqualTo("RESOLVER_FAILED");
            assertThat(result.entity()).isNull();
            assertThat(rig.fetches.get()).as(reason.name() + " reads nothing").isZero();
            assertThat(rig.resolverCalls).as(reason.name() + " tries no other resolver").containsExactly("parcel-to-depot");
            Metric metric = switch (reason) {
                case UNAVAILABLE -> Metric.RESOLVER_UNAVAILABLE;
                case TIMEOUT -> Metric.RESOLVER_TIMEOUT;
                case REJECTED -> Metric.RESOLVER_REJECTED;
                case TOO_MANY -> Metric.RESOLVER_TOO_MANY;
            };
            assertThat(rig.count(metric)).as(reason.name()).isEqualTo(1);
            assertThat(rig.lastAudit().sourceSystems()).anyMatch(s -> s.endsWith(":RESOLVER_FAILED"));
        }
    }

    @Test
    void aBlankKeyFromTheResolverIsResolverFailed() {
        FollowRig rig = rig(List.of("K-1", " "), false);

        assertThat(rig.follow(link(rig)).sources().get(rig.label("depot-api")).status()).isEqualTo("RESOLVER_FAILED");
        assertThat(rig.fetches.get()).isZero();
    }

    // ---- the outer status of a one-to-many follow (sealed-links.md section 7 item 24) ----

    private static SourceResult mix(FollowRig rig) {
        return rig.follow(link(rig)).sources().get(rig.label("depot-api"));
    }

    @Test
    void allAnsweredIsAnswered() {
        FollowRig rig = rig(KEYS.subList(0, 2), false);

        assertThat(mix(rig).status()).isEqualTo("ANSWERED");
    }

    @Test
    void someAnsweredAndTheRestNoDataIsAnsweredWithTheNoDataItemListed() {
        FollowRig rig = rig(KEYS.subList(0, 2), false);
        rig.records.remove("K-2");

        SourceResult result = mix(rig);

        assertThat(result.status()).isEqualTo("ANSWERED");
        assertThat(result.items()).extracting(SourceResult.Item::status).containsExactlyInAnyOrder("ANSWERED", "NO_DATA");
    }

    @Test
    void allNoDataIsNoData() {
        FollowRig rig = rig(KEYS.subList(0, 2), false);
        rig.records.clear();
        rig.records.put("other", new LinkBuilderTest.Plain("x"));

        assertThat(mix(rig).status()).isEqualTo("NO_DATA");
    }

    @Test
    void allFailedIsFailedAndMixedFailuresAreFailed() {
        FollowRig failed = rig(KEYS.subList(0, 2), false);
        failed.fetchFailure = key -> new IllegalStateException("down");
        FollowRig mixed = rig(KEYS.subList(0, 2), false);
        mixed.records.remove("K-1");
        mixed.fetchFailure = key -> key.equals("K-2") ? new IllegalStateException("down") : null;

        assertThat(mix(failed).status()).isEqualTo("FAILED");
        assertThat(mix(mixed).status()).isEqualTo("FAILED");
    }

    @Test
    void someRefusedAndSomeAnsweredOmitsTheRefusedAndAuditsItByIndex() {
        FollowRig rig = rig(KEYS.subList(0, 2), false);
        List<String> order = byFingerprint(rig, KEYS.subList(0, 2));
        Object refusedRecord = rig.records.get(order.get(0));
        rig.scrubFailure = record -> record == refusedRecord
                ? new PrivacyRefusedException("UNCLASSIFIED_FIELD", "$.x", "no") : null;

        SourceResult result = mix(rig);

        assertThat(result.status()).isEqualTo("ANSWERED");
        assertThat(result.items()).hasSize(1);
        assertThat(rig.lastAudit().policyDecision()).isEqualTo("ALLOW");
        assertThat(rig.lastAudit().fieldDispositions())
                .containsEntry("depot-api[0]:<refused>", "REFUSED:UNCLASSIFIED_FIELD");
    }

    @Test
    void everyAnsweredItemRefusedRefusesTheCallWithTheFirstRefusedCode() {
        FollowRig rig = rig(KEYS.subList(0, 2), false);
        rig.scrubFailure = record -> new PrivacyRefusedException("UNCLASSIFIED_FIELD", "$.x", "no");

        PrivacyRefusedException refused = FollowTest.refusal(rig, link(rig));

        assertThat(refused.code()).isEqualTo("UNCLASSIFIED_FIELD");
        assertThat(rig.lastAudit().policyDecision()).isEqualTo("DENY:UNCLASSIFIED_FIELD");
        assertThat(rig.lastAudit().fieldDispositions()).doesNotContainKey("merged:<refused>");
    }

    @Test
    void refusedPlusNoDataStillRefusesTheCallBecauseTheOnlyAnswerWasRefused() {
        FollowRig rig = rig(KEYS.subList(0, 2), false);
        rig.records.remove("K-2");
        rig.scrubFailure = record -> new PrivacyRefusedException("UNCLASSIFIED_FIELD", "$.x", "no");

        assertThat(FollowTest.refusal(rig, link(rig)).code()).isEqualTo("UNCLASSIFIED_FIELD");
    }
}
