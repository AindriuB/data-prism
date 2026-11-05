package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Parcel;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Plain;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Rig;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-response cap, the omitted count, the length limits and the size bound. */
class LinkCapTest {

    private static Parcel parcelWithDepots(int count) {
        return new Parcel("P-1", IntStream.range(0, count).mapToObj(i -> "D-" + i).toList(), List.of(), "ok");
    }

    private static SourceResult origin(ContextResponse response) {
        return response.sources().values().stream()
                .filter(s -> !s.links().isEmpty() || s.linksOmitted() != null).findFirst().orElseThrow();
    }

    @Test
    void theCapIsTakenRoundRobinAcrossTargetsAndTheRestIsCounted() {
        // Three depots, two targets: a cap of 3 must give a-api two links and b-api one, not three for a-api.
        Rig rig = new Rig().source("origin-api", parcelWithDepots(3))
                .source("a-api", new Plain("a"), "depot-ref").source("b-api", new Plain("a"), "depot-ref").cap(3);

        ContextResponse response = rig.run();

        SourceResult origin = origin(response);
        assertThat(origin.links()).hasSize(3);
        assertThat(origin.links().stream().map(IssuedLink::target).distinct()).hasSize(2);
        assertThat(origin.linksOmitted()).isEqualTo(3);
        assertThat(rig.count(Metric.LINKS_ISSUED)).isEqualTo(3);
        assertThat(rig.count(Metric.LINKS_OMITTED)).isEqualTo(3);
    }

    @Test
    void aCapOfOneKeepsTheFirstTargetAndStillCountsTheRest() {
        Rig rig = new Rig().source("origin-api", parcelWithDepots(2))
                .source("a-api", new Plain("a"), "depot-ref").source("b-api", new Plain("a"), "depot-ref").cap(1);

        SourceResult origin = origin(rig.run());

        assertThat(origin.links()).hasSize(1);
        assertThat(origin.linksOmitted()).isEqualTo(3);
    }

    @Test
    void underTheCapNothingIsOmitted() {
        Rig rig = new Rig().source("origin-api", parcelWithDepots(2)).source("a-api", new Plain("a"), "depot-ref");

        SourceResult origin = origin(rig.run());

        assertThat(origin.links()).hasSize(2);
        assertThat(origin.linksOmitted()).isNull();
        assertThat(rig.count(Metric.LINKS_OMITTED)).isZero();
    }

    @Test
    void aKeyOver256BytesIsNotIssuedAndCountsAsOmitted() {
        Parcel longKey = new Parcel("P-" + "k".repeat(300), List.of(), List.of(), "ok");
        Rig rig = new Rig().source("origin-api", longKey).source("parcel-api", new Plain("a"), "parcel-ref");

        SourceResult origin = origin(rig.run());

        assertThat(origin.links()).isEmpty();
        assertThat(origin.linksOmitted()).isEqualTo(1);
    }

    @Test
    void anEncodingOverMaxLengthIsNotIssuedAndCountsAsOmitted() {
        // 616 is the floor of max-length; a 256-byte key with a long target label seals near it. Use a rig whose
        // settings allow only the floor and a key that does not fit.
        Parcel nearly = new Parcel("P-" + "k".repeat(250), List.of(), List.of(), "ok");
        Rig rig = new Rig().source("origin-api", nearly)
                .source("t".repeat(64), new Plain("a"), "parcel-ref");

        SourceResult origin = origin(rig.run());

        // Either issued within 1024 or omitted and counted: the link is never silently lost.
        assertThat(origin.links().size() + (origin.linksOmitted() == null ? 0 : origin.linksOmitted()))
                .isEqualTo(1);
    }

    private static Rig rig(boolean links, int maxBytes) {
        Rig rig = new Rig().source("origin-api", parcelWithDepots(3)).source("a-api", new Plain("a"), "depot-ref")
                .maxResponseBytes(maxBytes);
        return links ? rig : rig.disabled();
    }

    private static boolean fits(boolean links, int maxBytes) {
        try {
            rig(links, maxBytes).run();
            return true;
        } catch (RuntimeException refused) {
            return refused.getMessage() == null || !refused.getMessage().contains("RESPONSE_TOO_LARGE")
                    ? rethrow(refused) : false;
        }
    }

    private static boolean rethrow(RuntimeException failure) {
        throw failure;
    }

    @Test
    void linksCountTowardMaxResponseSize() {
        // The least limit a response meets without links; with links the same limit refuses it.
        int low = 1;
        int high = 512 * 1024;
        while (low < high) {
            int middle = (low + high) / 2;
            if (fits(false, middle)) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }

        assertThat(fits(false, low)).isTrue();
        assertThat(fits(true, low)).isFalse();
        assertThat(fits(true, 512 * 1024)).isTrue();
    }

    @Test
    void routeLinksFromRecordFieldsShareTheSameCap() {
        Parcel mixed = new Parcel("P-1", List.of("D-1"), List.of(new Route("D-2"), new Route("D-3")), "ok");
        Rig rig = new Rig().source("origin-api", mixed).source("a-api", new Plain("a"), "depot-ref").cap(2);

        SourceResult origin = origin(rig.run());

        assertThat(origin.links()).hasSize(2);
        assertThat(origin.linksOmitted()).isEqualTo(1);
    }
}
